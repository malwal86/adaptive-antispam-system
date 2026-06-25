package com.antispam.stream;

import com.antispam.analyze.AnalyzeResponse;
import com.antispam.decision.DecisionMadeEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>The payload is the analyzer's own {@link AnalyzeResponse} — the same shape {@code POST /analyze}
 * returns — so the console renders a live card and a requested one identically. The stream is a thin
 * projection of the persisted decision; it never reimplements decision logic.
 */
@Component
public class DecisionStream {

    private static final Logger log = LoggerFactory.getLogger(DecisionStream.class);

    /** Recent decisions retained for reconnect replay. Generous enough for a demo's burst. */
    private static final int DEFAULT_BUFFER_CAPACITY = 512;
    /** Long-lived connection; liveness is maintained by the heartbeat, not a short timeout. */
    private static final long EMITTER_TIMEOUT_MS = TimeUnit.HOURS.toMillis(1);
    /** Keep-alive cadence — under typical proxy idle timeouts, and cheap. */
    private static final long HEARTBEAT_SECONDS = 20;

    /** A decision paired with the monotonic sequence id used for SSE resume. */
    public record Sequenced(long seq, AnalyzeResponse decision) {}

    private final int bufferCapacity;
    private final AtomicLong sequence = new AtomicLong(0);
    private final Deque<Sequenced> buffer = new ArrayDeque<>();
    private final Set<SseEmitter> subscribers = ConcurrentHashMap.newKeySet();
    private final ExecutorService fanOut =
            Executors.newSingleThreadExecutor(daemon("decision-stream-fanout"));
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(daemon("decision-stream-heartbeat"));

    public DecisionStream() {
        this(DEFAULT_BUFFER_CAPACITY);
    }

    DecisionStream(int bufferCapacity) {
        this.bufferCapacity = bufferCapacity;
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
        Sequenced recorded = record(AnalyzeResponse.from(event.classification(), false));
        broadcast(recorded);
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

    private synchronized Sequenced record(AnalyzeResponse decision) {
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

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
