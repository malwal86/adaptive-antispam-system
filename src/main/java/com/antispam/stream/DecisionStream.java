package com.antispam.stream;

import com.antispam.analyze.AnalyzeResponse;
import com.antispam.decision.Classification;
import com.antispam.decision.DecisionMadeEvent;
import com.antispam.features.EmailFeatureExtractor;
import com.antispam.ingest.Email;
import com.antispam.ingest.EmailRepository;
import com.antispam.ingest.ParsedEmail;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The live decision feed behind {@code GET /decisions/stream} (story 12.01). It turns the in-process
 * {@link DecisionMadeEvent}s the pipeline publishes into Server-Sent Events for every connected
 * console, and gives the transport its two reliability properties:
 *
 * <ul>
 *   <li><b>Resumable, no duplicates.</b> Every decision gets a strictly increasing sequence id,
 *       emitted as the SSE {@code id:} field. On reconnect the browser replays its last id via the
 *       {@code Last-Event-ID} header; we replay only the buffered events <em>after</em> it. A bounded
 *       ring buffer (the {@code bufferCapacity}) caps memory — so a client offline longer than the
 *       buffer misses the evicted events (documented, lost-forever), but never sees a duplicate.</li>
 *   <li><b>Non-blocking.</b> Fan-out to subscribers and keep-alive heartbeats run on dedicated
 *       daemon threads, never the decision thread, so a slow console can never add latency to a
 *       decision. A subscriber whose send fails is simply dropped.</li>
 * </ul>
 *
 * <p>The payload is a {@link LiveDecision} — the analyzer's own {@link AnalyzeResponse} flattened,
 * plus, for synthetic scenario/demo mail only, the human-readable envelope (from · subject · preview)
 * the console cards read like email. The stream is a thin projection of the persisted decision; it
 * never reimplements decision logic. For ordinary traffic the envelope fields are omitted, so the
 * payload is byte-for-byte the same shape {@code POST /analyze} returns.
 */
@Component
public class DecisionStream {

    /** Recent decisions retained for reconnect replay. Generous enough for a demo's burst. */
    private static final int DEFAULT_BUFFER_CAPACITY = 512;
    /** Long-lived connection; liveness is maintained by the heartbeat, not a short timeout. */
    private static final long EMITTER_TIMEOUT_MS = TimeUnit.HOURS.toMillis(1);
    /** Keep-alive cadence — under typical proxy idle timeouts, and cheap. */
    private static final long HEARTBEAT_SECONDS = 20;

    /** A decision paired with the monotonic sequence id used for SSE resume. */
    public record Sequenced(long seq, LiveDecision decision) {}

    /**
     * Looks up an email by id so a scenario decision can be enriched with its envelope. A seam (not
     * the repository directly) so the transport is unit-testable without a database — the production
     * bean binds it to {@link EmailRepository#findById}.
     */
    @FunctionalInterface
    interface EmailLookup {
        Optional<Email> find(UUID emailId);
    }

    private final int bufferCapacity;
    private final EmailLookup emails;
    private final AtomicLong sequence = new AtomicLong(0);
    private final Deque<Sequenced> buffer = new ArrayDeque<>();
    private final Set<SseEmitter> subscribers = ConcurrentHashMap.newKeySet();
    private final ExecutorService fanOut =
            Executors.newSingleThreadExecutor(daemon("decision-stream-fanout"));
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(daemon("decision-stream-heartbeat"));

    @Autowired
    public DecisionStream(EmailRepository emails) {
        this(DEFAULT_BUFFER_CAPACITY, emails::findById);
    }

    /** Test seam: a chosen buffer capacity and no enrichment (envelope stays empty). */
    DecisionStream(int bufferCapacity) {
        this(bufferCapacity, id -> Optional.empty());
    }

    DecisionStream(int bufferCapacity, EmailLookup emails) {
        this.bufferCapacity = bufferCapacity;
        this.emails = emails;
    }

    @PostConstruct
    void startHeartbeat() {
        heartbeat.scheduleAtFixedRate(
                this::sendHeartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        heartbeat.shutdownNow();
        fanOut.shutdownNow();
        subscribers.forEach(SseEmitter::complete);
    }

    /** Records a decision (assigning its sequence id) and fans it out to connected consoles. */
    @EventListener
    public void onDecision(DecisionMadeEvent event) {
        Sequenced recorded = record(project(event.classification()));
        broadcast(recorded);
    }

    /**
     * Projects a persisted decision onto the live payload, enriching it with the email's envelope
     * (from · subject · preview) only for synthetic scenario mail. The email lookup runs here, on the
     * event-listener thread (off the decision's critical path), and any failure or missing email
     * degrades cleanly to the bare verdict — the card simply renders without an envelope rather than
     * dropping the decision.
     */
    private LiveDecision project(Classification classification) {
        AnalyzeResponse verdict = AnalyzeResponse.from(classification, false);
        Email email = findEmail(classification.emailId());
        if (email == null || !isScenarioSource(email.ingestSource())) {
            return LiveDecision.of(verdict); // ordinary traffic stays PII-free
        }
        ParsedEmail metadata = email.metadata();
        String address = metadata == null ? null : metadata.sender();
        String subject = metadata == null ? null : metadata.subject();
        return new LiveDecision(
                verdict, senderDisplay(email.rawContent(), address), subject, preview(email.rawContent()));
    }

    private Email findEmail(UUID emailId) {
        try {
            return emails.find(emailId).orElse(null);
        } catch (RuntimeException e) {
            // Enrichment is best-effort: a lookup failure must never drop the decision from the feed.
            return null;
        }
    }

    /**
     * Opens a subscription, replaying any buffered decisions newer than {@code lastEventId} (0 to
     * start from the oldest retained) so a reconnecting client resumes without gaps or duplicates.
     */
    public SseEmitter subscribe(long lastEventId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> {
            subscribers.remove(emitter);
            emitter.complete();
        });
        emitter.onError(e -> subscribers.remove(emitter));
        subscribers.add(emitter);
        for (Sequenced missed : bufferedSince(lastEventId)) {
            if (!send(emitter, missed)) {
                break;
            }
        }
        return emitter;
    }

    private synchronized Sequenced record(LiveDecision decision) {
        Sequenced recorded = new Sequenced(sequence.incrementAndGet(), decision);
        buffer.addLast(recorded);
        while (buffer.size() > bufferCapacity) {
            buffer.removeFirst();
        }
        return recorded;
    }

    /** The buffered decisions with a sequence id strictly greater than {@code lastEventId}. */
    synchronized List<Sequenced> bufferedSince(long lastEventId) {
        List<Sequenced> missed = new ArrayList<>();
        for (Sequenced candidate : buffer) {
            if (candidate.seq() > lastEventId) {
                missed.add(candidate);
            }
        }
        return missed;
    }

    long lastEventId() {
        return sequence.get();
    }

    int subscriberCount() {
        return subscribers.size();
    }

    private void broadcast(Sequenced event) {
        fanOut.execute(() -> {
            for (SseEmitter emitter : subscribers) {
                send(emitter, event);
            }
        });
    }

    private boolean send(SseEmitter emitter, Sequenced event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.seq()))
                    .name("decision")
                    .data(event.decision()));
            return true;
        } catch (IOException | IllegalStateException e) {
            // A dropped or already-completed client is expected churn, not an error to act on:
            // forget it and let the browser reconnect (resuming from its Last-Event-ID).
            subscribers.remove(emitter);
            return false;
        }
    }

    private void sendHeartbeat() {
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException e) {
                subscribers.remove(emitter);
            }
        }
    }

    // ---- Envelope enrichment (scenario mail only) --------------------------

    /** Ingest sources whose mail is synthetic demo content safe to surface on the shared feed. */
    private static final List<String> SCENARIO_SOURCE_PREFIXES = List.of("thunderclap-", "normal-morning-");
    /** Captures the value of the (first) {@code From:} header from the raw message. */
    private static final Pattern FROM_HEADER = Pattern.compile("(?im)^From:\\s*(.+)$");
    /** How much of the body to preview on a card — one glanceable line. */
    private static final int PREVIEW_MAX_CHARS = 140;

    /**
     * Whether this email is scenario/demo mail whose envelope is safe to show. Fail-closed: an
     * unknown source is treated as ordinary traffic and stays PII-free.
     */
    static boolean isScenarioSource(String ingestSource) {
        if (ingestSource == null) {
            return false;
        }
        for (String prefix : SCENARIO_SOURCE_PREFIXES) {
            if (ingestSource.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The friendly "from" for a card: the {@code From} header's display name (e.g. {@code Mom} from
     * {@code Mom <mom@family.example>}), falling back to the bare address when there is no display
     * name. Total: a null/unparseable message yields the fallback.
     */
    static String senderDisplay(byte[] rawContent, String fallbackAddress) {
        if (rawContent == null || rawContent.length == 0) {
            return fallbackAddress;
        }
        var matcher = FROM_HEADER.matcher(new String(rawContent, StandardCharsets.UTF_8));
        if (matcher.find()) {
            String value = matcher.group(1).trim();
            int angle = value.indexOf('<');
            if (angle > 0) {
                String name = stripQuotes(value.substring(0, angle).trim());
                if (!name.isBlank()) {
                    return name;
                }
            }
        }
        return fallbackAddress;
    }

    /** A short, plain-text body preview: the decoded display text, collapsed and truncated. */
    static String preview(byte[] rawContent) {
        String body = EmailFeatureExtractor.displayText(rawContent);
        if (body == null || body.isBlank()) {
            return null;
        }
        String collapsed = body.replaceAll("\\s+", " ").strip();
        return collapsed.length() <= PREVIEW_MAX_CHARS
                ? collapsed
                : collapsed.substring(0, PREVIEW_MAX_CHARS - 1).stripTrailing() + "…";
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
