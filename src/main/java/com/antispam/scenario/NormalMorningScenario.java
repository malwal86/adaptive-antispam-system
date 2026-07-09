package com.antispam.scenario;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.springframework.stereotype.Component;

/**
 * "An everyday inbox": the calm, legible demo anyone can read at a glance. A handful of real-looking
 * emails arrive — a note from Mom, a store newsletter, an order receipt — and land in the inbox,
 * while two obvious scams (a fake bank alert and a prize scam) are blocked outright, and one
 * borderline "your package is held" notice is briefly checked before it, too, is stopped. It is the
 * everyday split — most mail waved through, the clear-cut abuse stopped — with none of the analyst
 * jargon.
 *
 * <p>Two design choices make the run <em>readable</em> rather than a column of "checking…" cards:
 * <ul>
 *   <li>The good senders are {@link #prewarm() pre-warmed} with authenticated good reputation, so
 *       their benign mail is a confident, instant inbox decision on the model route — not escalated
 *       to the LLM the way every brand-new sender otherwise is.</li>
 *   <li>The two flagrant scams link to <em>denylisted</em> hosts, so a hard rule blocks them
 *       immediately (no model, no LLM) — a decisive, explainable "this is a scam".</li>
 * </ul>
 * Exactly one email — the delivery-notice — is left an unseen sender with mildly suspicious content,
 * so it is the single deliberate "checked, then decided" beat. Every email still travels the same
 * live pipeline; the verdicts are real backend decisions, never scripted. Like every scenario it is a
 * pure, seeded function of the seed (the ref tokens vary; the beats and senders are fixed).
 */
@Component
public class NormalMorningScenario implements Scenario {

    /** The stable id used by the API path and the console's picker. */
    public static final String NAME = "a_normal_morning";

    private static final String INBOX = "you@inbox.example";
    /** A fixed base instant (not {@code now}) keeps the built bytes seeded and reproducible. */
    private static final Instant BASE = Instant.parse("2026-06-25T08:00:00Z");

    // The three genuinely-good senders (display name + address). Pre-warmed so their mail lands in the
    // inbox instantly; the display name is what the console card shows as the "from".
    private static final String MOM = "Mom <mom@family.example>";
    private static final String STORE = "Acme Store <weekly@acme-store.example>";
    private static final String RECEIPT = "Rivertown Goods <receipts@rivertown-goods.example>";
    // Their bare addresses, matching SenderKey.of(...) exactly, for pre-warming reputation.
    private static final String MOM_KEY = "mom@family.example";
    private static final String STORE_KEY = "weekly@acme-store.example";
    private static final String RECEIPT_KEY = "receipts@rivertown-goods.example";
    /** One high-weight good signal drops a sender's uncertainty below the routing band in one write. */
    private static final double WARMUP_WEIGHT = 20.0;

    /** Denylisted look-alike hosts (see {@code antispam.hard-rules.url-denylist}); a link to either → BLOCK. */
    static final String BANK_SCAM_HOST = "secure-bank-verify.example";
    static final String PRIZE_SCAM_HOST = "prize-claim-center.example";

    private static final java.time.format.DateTimeFormatter RFC_822_DATE =
            java.time.format.DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Scenario.SenderWarmup> prewarm() {
        // The good senders start trusted so their benign mail is an instant inbox decision, not an
        // LLM escalation. The scam and delivery-notice senders are deliberately left unseeded.
        return List.of(
                new Scenario.SenderWarmup(MOM_KEY, WARMUP_WEIGHT),
                new Scenario.SenderWarmup(STORE_KEY, WARMUP_WEIGHT),
                new Scenario.SenderWarmup(RECEIPT_KEY, WARMUP_WEIGHT));
    }

    @Override
    public List<ScenarioEmail> build(long seed) {
        Random rng = new Random(seed);
        List<ScenarioEmail> script = new ArrayList<>();
        int minute = 0;

        // A note from Mom: authenticated, personal, no links — plainly delivered to the inbox.
        script.add(email(Beat.LEGIT, MOM, null,
                "Dinner Sunday?",
                "Hi love — are you free for dinner this Sunday? Dad's making his lasagne. Let me know "
                        + "and I'll get the ingredients. No rush.\n\nLove, Mom xx",
                Auth.PASS, BASE.plusSeconds(60L * minute++)));

        // A store newsletter: authenticated bulk mail with one ordinary link — allowed, not punished
        // for being promotional (a single normal https link is not a phishing tell).
        script.add(email(Beat.NEWSLETTER, STORE, null,
                "This week's deals — up to 30% off",
                "Good morning! Here are this week's picks, hand-chosen for you.\n\nBrowse the sale: "
                        + "https://acme-store.example/weekly/" + ref(rng)
                        + "\n\nYou're receiving this because you subscribed. Unsubscribe any time.",
                Auth.PASS, BASE.plusSeconds(60L * minute++)));

        // A fake "your bank" alert: links to a denylisted look-alike host, so a hard rule blocks it
        // outright — the decisive "this is a scam", no model or LLM needed.
        script.add(email(Beat.SPAM, "Your Bank Security <security@your-bank-alerts.example>",
                "no-reply@your-bank-alerts.example",
                "Action required: verify your account now",
                "We detected unusual activity on your account. To avoid suspension you must verify "
                        + "your details immediately: http://" + BANK_SCAM_HOST + "/login?ref=" + ref(rng)
                        + "\n\nFailure to verify within 24 hours will lock your account.",
                Auth.FAIL, BASE.plusSeconds(60L * minute++)));

        // A genuine order receipt: authenticated, transactional, benign — delivered.
        script.add(email(Beat.LEGIT, RECEIPT, null,
                "Your order has shipped",
                "Thanks for your order! Your parcel is on its way and should arrive in 2–3 days. No "
                        + "action needed — this note is just for your records.\n\nRivertown Goods",
                Auth.PASS, BASE.plusSeconds(60L * minute++)));

        // A classic prize scam: shouty, and links to a denylisted host — blocked outright.
        script.add(email(Beat.SPAM, "Prize Department <winner@prize-claim-center.example>",
                "claims@prize-claim-center.example",
                "CONGRATULATIONS!!! You have WON a $1,000 gift card",
                "Dear WINNER!! Your email was SELECTED in our monthly draw. CLAIM YOUR $1,000 GIFT "
                        + "CARD NOW before it EXPIRES: http://" + PRIZE_SCAM_HOST + "/claim?code=" + ref(rng)
                        + "\n\nACT FAST — this offer will NOT be repeated!!",
                Auth.FAIL, BASE.plusSeconds(60L * minute++)));

        // A borderline "package held" notice from an unseen sender with a plain (not denylisted) link
        // and manufactured urgency. Being a brand-new sender, it is checked by the LLM
        // (quarantine-pending) and then resolved — the one deliberate "system thought about it" beat.
        script.add(email(Beat.SPAM, "Parcel Notice <notice@parcel-redelivery.example>",
                null,
                "Your package is being held — reschedule delivery",
                "We attempted to deliver your parcel but could not complete it. Please confirm your "
                        + "delivery preferences to avoid it being returned: "
                        + "https://parcel-redelivery.example/reschedule/" + ref(rng)
                        + "\n\nPlease respond within 48 hours.",
                Auth.FAIL, BASE.plusSeconds(60L * minute++)));

        return List.copyOf(script);
    }

    // ---- Raw RFC-822 assembly ----------------------------------------------

    /** SPF/DKIM/DMARC verdicts written into the {@code Authentication-Results} header. */
    private enum Auth {
        PASS("pass", "pass", "pass"),
        FAIL("fail", "fail", "fail");

        final String spf;
        final String dkim;
        final String dmarc;

        Auth(String spf, String dkim, String dmarc) {
            this.spf = spf;
            this.dkim = dkim;
            this.dmarc = dmarc;
        }
    }

    private static ScenarioEmail email(Beat beat, String from, String replyTo, String subject,
            String body, Auth auth, Instant date) {
        String authResults = "mx.inbox.example; spf=" + auth.spf + "; dkim=" + auth.dkim
                + "; dmarc=" + auth.dmarc;
        String replyToHeader = replyTo == null ? "" : "Reply-To: " + replyTo + "\r\n";
        String message = "From: " + from + "\r\n"
                + replyToHeader
                + "To: " + INBOX + "\r\n"
                + "Subject: " + subject + "\r\n"
                + "Date: " + RFC_822_DATE.format(ZonedDateTime.ofInstant(date, ZoneOffset.UTC)) + "\r\n"
                + "Authentication-Results: " + authResults + "\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + body.replace("\n", "\r\n") + "\r\n";
        return new ScenarioEmail(beat, "normal-morning-" + beat.name().toLowerCase(Locale.US),
                message.getBytes(StandardCharsets.UTF_8));
    }

    /** A 6-digit reference token drawn from the seed, so each run's content is reproducible yet varied. */
    private static String ref(Random rng) {
        return String.format("%06d", rng.nextInt(1_000_000));
    }
}
