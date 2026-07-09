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
 * "A normal morning": routine inbox triage, no dramatic beat. A couple of genuinely legitimate,
 * authenticated emails and an authenticated newsletter arrive alongside two pieces of obvious junk
 * from unknown, unauthenticated senders — so the console shows the everyday split (most mail waved
 * through, the clear-cut spam stopped) rather than the thunderclap's rise-and-collapse.
 *
 * <p>It teaches the calm case the {@link SenderTurnsHostileScenario dramatic one} does not: the system
 * is not trigger-happy — well-formed, authenticated mail (including bulk newsletters) is allowed,
 * while unauthenticated, shouty spam is blocked on content. Like every scenario it is a pure, seeded
 * function of the seed: the ref tokens vary with it so re-runs aren't identical, but the beats and
 * verdicts are fixed. Every email travels the same live pipeline; the {@code source} tag just makes
 * this scenario's mail auditable apart from the thunderclap's.
 */
@Component
public class NormalMorningScenario implements Scenario {

    /** The stable id used by the API path and the console's picker. */
    public static final String NAME = "a_normal_morning";

    private static final String INBOX = "you@inbox.example";
    /** A fixed base instant (not {@code now}) keeps the built bytes seeded and reproducible. */
    private static final Instant BASE = Instant.parse("2026-06-25T08:00:00Z");

    private static final java.time.format.DateTimeFormatter RFC_822_DATE =
            java.time.format.DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<ScenarioEmail> build(long seed) {
        Random rng = new Random(seed);
        List<ScenarioEmail> script = new ArrayList<>();
        int minute = 0;

        // A genuinely legitimate, authenticated colleague — benign, no links: plainly allowed.
        script.add(email(Beat.LEGIT, "priya@partner-co.example", null,
                "Re: Thursday sync moved to 2pm",
                "Hi — just confirming the sync is now at 2pm Thursday. I moved the doc link into the "
                        + "calendar invite. See you then!\n\nPriya",
                Auth.PASS, BASE.plusSeconds(60L * minute++)));

        // An authenticated newsletter: bulk, but well-formed with an ordinary link — allowed, not
        // punished for volume. (A single normal https link is not a phishing tell.)
        script.add(email(Beat.NEWSLETTER, "brief@morning-brief.example", null,
                "The Morning Brief — Tuesday",
                "Good morning! Today's three stories, in two minutes.\n\nRead the full brief: "
                        + "https://morning-brief.example/issues/" + ref(rng)
                        + "\n\nYou are receiving this because you subscribed. Unsubscribe any time.",
                Auth.PASS, BASE.plusSeconds(60L * minute++)));

        // A legitimate transactional receipt, authenticated, benign — allowed.
        script.add(email(Beat.LEGIT, "receipts@rivertown-coffee.example", null,
                "Your receipt from Rivertown Coffee",
                "Thanks for stopping by! You paid $4.75 for one flat white. No action needed — this "
                        + "receipt is for your records.\n\nRivertown Coffee",
                Auth.PASS, BASE.plusSeconds(60L * minute++)));

        // Obvious junk from an unknown, unauthenticated sender: shouty, a bait link — blocked on content.
        script.add(email(Beat.SPAM, "rewards@prize-claim-center.example",
                "claims@prize-payout-desk.example",
                "CONGRATULATIONS!!! You have WON a $1,000 GIFT CARD",
                "Dear WINNER!! Your email was SELECTED in our monthly draw. CLAIM YOUR $1,000 GIFT "
                        + "CARD NOW before it EXPIRES: http://203.0.113.7/claim/reward?ref=" + ref(rng)
                        + "&code=" + ref(rng) + "\n\nACT FAST — this offer will NOT be repeated!!",
                Auth.FAIL, BASE.plusSeconds(60L * minute++)));

        // A second, equally clear scam — a fake delivery notice — also unauthenticated and blocked.
        script.add(email(Beat.SPAM, "alerts@parcel-redelivery.example",
                "support@parcel-redelivery-billing.example",
                "Your PACKAGE could not be delivered — CLICK NOW to reschedule",
                "URGENT: your parcel is being HELD. A small redelivery fee is REQUIRED. Confirm your "
                        + "details IMMEDIATELY: http://198.51.100.9/redelivery/confirm?ref=" + ref(rng)
                        + "\n\nFailure to respond within 24 HOURS will return your package to sender.",
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
