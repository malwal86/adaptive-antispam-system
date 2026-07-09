package com.antispam.scenario;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Builds the {@code sender_warms_up_then_attacks} thunderclap scenario (story 12.05) as an ordered
 * list of raw RFC-822 emails — the pure, deterministic core the runner feeds through the live
 * pipeline.
 *
 * <p>The script is a single seeded function of {@link Plan} and a seed: same seed, same bytes, so the
 * demo is exactly reproducible (an acceptance criterion) while a different seed re-mutates the
 * phishing campaign so successive live runs aren't carbon copies. It touches no clock and no random
 * source beyond the supplied seed, so it is safe to call anywhere and trivial to test.
 *
 * <p>The four phases, in order, are the demo's beats (see {@link Beat}). The hero sender
 * ({@link #WARM_DOMAIN}) warms up with benign authenticated mail, then turns bad with a mutated,
 * DMARC-aligned phishing campaign from that <em>same</em> domain — so the reputation curve that rose
 * is the one that collapses. A separate unauthenticated {@link Beat#SPOOF} of the warm domain proves
 * auth gating denies it the earned trust, and a {@link #LEGIT_DOMAIN} sender with broken auth but
 * good content proves the gate is soft.
 */
public final class ThunderclapScript {

    /** The one scenario this builder knows; the name the console's picker and the API path use. */
    public static final String NAME = "sender_warms_up_then_attacks";

    /** The hero sender that warms up and is then both compromised and impersonated. */
    public static final String WARM_DOMAIN = "acme-mail.example";

    /** A genuinely legitimate sender whose authentication is misconfigured. */
    public static final String LEGIT_DOMAIN = "smallbiz-supply.example";

    /** The recipient every scenario email is addressed to — the lab's demo inbox. */
    private static final String INBOX = "you@inbox.example";

    /**
     * Burst arrivals are dated close together, the warm-up history further back, so the timeline reads
     * the way the story does. The base instant is fixed (not {@code now}) to keep the bytes seeded.
     */
    private static final Instant BASE = Instant.parse("2026-06-25T09:00:00Z");

    private static final DateTimeFormatter RFC_822_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);

    /** Seconds between successive warm-up arrivals, walking backwards from {@link #BASE}. */
    private static final int WARMUP_SPACING_SECONDS = 3600;

    private ThunderclapScript() {
    }

    /**
     * How many emails each phase contributes. A campaign needs more than one variant to be a campaign
     * (and to show mutation), and every phase must contribute at least one email or its beat would
     * never land.
     *
     * @param warmUps           authenticated benign emails that raise the hero's reputation (≥ 1)
     * @param attackVariants    mutated phishing emails in the campaign (≥ 2)
     * @param misconfiguredLegit benign emails from the broken-auth legitimate sender (≥ 1)
     */
    public record Plan(int warmUps, int attackVariants, int misconfiguredLegit) {

        /** A demo-tuned default: enough warm-up to rise visibly, a short burst, a few legit messages. */
        public static final Plan DEFAULT = new Plan(8, 6, 3);

        public Plan {
            require(warmUps >= 1, "warmUps must be at least 1");
            require(attackVariants >= 2, "attackVariants must be at least 2 (a campaign, with mutation)");
            require(misconfiguredLegit >= 1, "misconfiguredLegit must be at least 1");
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new IllegalArgumentException(message);
            }
        }
    }

    /** Builds the scenario with the {@link Plan#DEFAULT default plan}. */
    public static List<ScenarioEmail> build(long seed) {
        return build(seed, Plan.DEFAULT);
    }

    /** Builds the scenario: warm-ups, then the attack campaign, then the spoof, then the legit sender. */
    public static List<ScenarioEmail> build(long seed, Plan plan) {
        Random rng = new Random(seed);
        List<ScenarioEmail> script = new ArrayList<>(
                plan.warmUps() + plan.attackVariants() + 1 + plan.misconfiguredLegit());

        for (int i = 0; i < plan.warmUps(); i++) {
            script.add(warmUp(i, rng));
        }
        for (int i = 0; i < plan.attackVariants(); i++) {
            script.add(attack(i, rng));
        }
        script.add(spoof(rng));
        for (int i = 0; i < plan.misconfiguredLegit(); i++) {
            script.add(misconfiguredLegit(i, rng));
        }
        return List.copyOf(script);
    }

    // ---- Phases -------------------------------------------------------------

    private static ScenarioEmail warmUp(int index, Random rng) {
        String[] subjects = {
            "Your weekly account summary",
            "Receipt for your subscription",
            "What's new at Acme this week",
            "Your statement is ready",
            "Thanks for being a member",
        };
        String subject = subjects[index % subjects.length] + " (#" + ref(rng) + ")";
        String body = "Hi there,\n\nHere is your routine update from the Acme team. "
                + "Nothing needs your attention — this note is for your records.\n\nWarm regards,\nThe Acme Team";
        // Warm history trails the present so the curve has a visible rise before the burst.
        Instant date = BASE.minusSeconds((long) WARMUP_SPACING_SECONDS * (index + 1));
        return email(Beat.WARMUP, "updates@" + WARM_DOMAIN, subject, body, AuthResult.PASS, date);
    }

    private static ScenarioEmail attack(int index, Random rng) {
        // The warmed sender's account, now compromised. The mail still AUTHENTICATES — DMARC stays
        // aligned, so an auth check alone waves it through and the earned reputation is on the line —
        // but the content is unmistakable phishing: a raw-IP credential-harvesting link, a punycode
        // look-alike link, an off-domain Reply-To, and shouty urgency. Those are exactly the
        // structural tells the content model scores high, so the reputation curve that rose now
        // collapses despite the passing auth (the demo's whole point). A near-duplicate campaign:
        // mutated per variant (the ref token and a swapped urgency phrase) so the bodies differ yet
        // cluster — what burst/campaign detection sees.
        String[] urgency = {"within 24 HOURS", "IMMEDIATELY", "before END OF DAY", "RIGHT AWAY"};
        String phrase = urgency[index % urgency.length];
        String token = ref(rng);
        String subject = "URGENT: your ACME account will be SUSPENDED — VERIFY " + phrase + "!";
        // A dotted-quad host (never a real Acme URL) with a long, obfuscated query — the classic tell.
        String ipLink = "http://198.51.100.42/acme/secure/session/verify?ref=" + token
                + "&id=" + ref(rng) + "&continue=account-security-settings-review";
        // A punycode look-alike domain, the second phishing tell in the same message.
        String punyLink = "http://xn--acme-secure-login.example/confirm?ref=" + token;
        // Replies go off-domain to the attacker's collection address, not back to the warmed sender.
        String replyTo = "account-recovery@acme-security-alerts.example";
        String body = "SECURITY ALERT!! We detected unusual activity on your account and access will be "
                + "SUSPENDED " + phrase + ".\n\nConfirm your identity NOW to avoid losing access: " + ipLink
                + "\n\nMobile users, use this secure link instead: " + punyLink
                + "\n\nDO NOT IGNORE THIS MESSAGE.\n\nAccount Security, ACME";
        // The burst arrives clustered around the present moment.
        Instant date = BASE.plusSeconds(index);
        return email(Beat.ATTACK, "alerts@" + WARM_DOMAIN, replyTo, subject, body, AuthResult.PASS, date);
    }

    private static ScenarioEmail spoof(Random rng) {
        // Impersonates the warmed domain but fails authentication — the From domain matches, the auth
        // results do not, so reputation gating gives it none of the earned trust.
        String link = "http://acme-mail-security.example/reset?ref=" + ref(rng);
        String body = "Your password has expired. Reset it now to avoid losing access: " + link;
        return email(Beat.SPOOF, "security@" + WARM_DOMAIN,
                "Your Acme password has expired", body, AuthResult.FAIL, BASE.plusSeconds(60));
    }

    private static ScenarioEmail misconfiguredLegit(int index, Random rng) {
        // A real small business whose mail server never set up SPF/DKIM/DMARC. The content is plainly
        // benign; the soft gate lets it accrue trust slowly rather than dropping it.
        String subject = "Invoice #" + (1000 + index) + " from Smallbiz Supply";
        String body = "Hello,\n\nPlease find attached invoice #" + (1000 + index) + " for your recent order. "
                + "Payment terms are net 30. Thank you for your business!\n\nAccounts, Smallbiz Supply ("
                + ref(rng) + ")";
        Instant date = BASE.plusSeconds(120L + index);
        return email(Beat.MISCONFIGURED_LEGIT, "billing@" + LEGIT_DOMAIN, subject, body,
                AuthResult.NONE, date);
    }

    // ---- Raw RFC-822 assembly ----------------------------------------------

    /** SPF/DKIM/DMARC verdicts written into the {@code Authentication-Results} header. */
    private enum AuthResult {
        PASS("pass", "pass", "pass"),
        FAIL("fail", "fail", "fail"),
        NONE("none", "none", "none");

        final String spf;
        final String dkim;
        final String dmarc;

        AuthResult(String spf, String dkim, String dmarc) {
            this.spf = spf;
            this.dkim = dkim;
            this.dmarc = dmarc;
        }
    }

    private static ScenarioEmail email(Beat beat, String from, String subject, String body,
            AuthResult auth, Instant date) {
        return email(beat, from, null, subject, body, auth, date);
    }

    private static ScenarioEmail email(Beat beat, String from, String replyTo, String subject,
            String body, AuthResult auth, Instant date) {
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
        return new ScenarioEmail(beat, message.getBytes(StandardCharsets.UTF_8));
    }

    /** A 6-digit reference token drawn from the seed, so each message carries a reproducible mutation. */
    private static String ref(Random rng) {
        return String.format("%06d", rng.nextInt(1_000_000));
    }
}
