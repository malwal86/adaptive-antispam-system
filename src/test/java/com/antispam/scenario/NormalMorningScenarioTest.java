package com.antispam.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.event.SenderKey;
import com.antispam.features.EmailFeatureExtractor;
import com.antispam.ingest.EmailParser;
import com.antispam.ingest.ParsedEmail;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The "an everyday inbox" scenario's pure, seeded core: a note from Mom, a newsletter, and a receipt
 * land in the inbox, two flagrant scams link to denylisted hosts (so a hard rule blocks them), and one
 * borderline delivery-notice is left an unseen sender for the single "checked, then decided" beat.
 * These assert the structural contract the calm demo depends on — the right beats, the authentication,
 * the denylisted scam links, the pre-warmed good senders, and reproducibility from the seed — leaving
 * the live-pipeline verdicts to the runner and the model.
 */
class NormalMorningScenarioTest {

    private static final EmailParser PARSER = new EmailParser();
    private static final NormalMorningScenario SCENARIO = new NormalMorningScenario();

    private static String dmarc(byte[] raw) {
        ParsedEmail md = PARSER.parse(raw);
        return EmailFeatureExtractor.authFeatures(md.authResults()).dmarc();
    }

    private static int urlCount(byte[] raw) {
        return EmailFeatureExtractor.linkFeatures(new String(raw, StandardCharsets.UTF_8)).urlCount();
    }

    private static String text(byte[] raw) {
        return new String(raw, StandardCharsets.UTF_8);
    }

    @Test
    void it_is_named_for_the_api_path_and_picker() {
        assertThat(SCENARIO.name()).isEqualTo("a_normal_morning");
    }

    @Test
    void a_morning_is_good_mail_two_scams_and_one_borderline_notice() {
        List<ScenarioEmail> script = SCENARIO.build(1L);

        // Mom, newsletter, fake-bank scam, receipt, prize scam, borderline delivery-notice.
        assertThat(script.stream().map(ScenarioEmail::beat))
                .containsExactly(Beat.LEGIT, Beat.NEWSLETTER, Beat.SPAM, Beat.LEGIT, Beat.SPAM, Beat.SPAM);
        // Each email is tagged with this scenario's own provenance, not the thunderclap's.
        assertThat(script).allSatisfy(e -> assertThat(e.source()).startsWith("normal-morning-"));
    }

    @Test
    void every_email_is_a_parseable_message() {
        for (ScenarioEmail email : SCENARIO.build(7L)) {
            ParsedEmail md = PARSER.parse(email.raw());
            assertThat(md.sender()).as("sender parses").isNotNull();
            assertThat(md.senderDomain()).as("domain parses").isNotBlank();
            assertThat(md.subject()).as("subject parses").isNotBlank();
        }
    }

    @Test
    void the_good_mail_is_authenticated_and_lands_in_the_inbox() {
        List<ScenarioEmail> good = SCENARIO.build(3L).stream()
                .filter(e -> e.beat() == Beat.LEGIT || e.beat() == Beat.NEWSLETTER)
                .toList();

        // Mom, the receipt, and the newsletter: authenticated, no scary links (the newsletter carries a
        // single ordinary https link, which is not a phishing tell).
        assertThat(good).hasSize(3);
        for (ScenarioEmail email : good) {
            assertThat(dmarc(email.raw())).as("authenticated").isEqualTo("pass");
            assertThat(urlCount(email.raw())).as("no more than one ordinary link").isLessThanOrEqualTo(1);
        }
    }

    @Test
    void the_two_flagrant_scams_link_to_denylisted_hosts_so_a_hard_rule_blocks_them() {
        List<ScenarioEmail> spam = SCENARIO.build(3L).stream()
                .filter(e -> e.beat() == Beat.SPAM)
                .toList();

        // Three SPAM-beat emails: two flagrant scams (denylisted links → hard-rule BLOCK) and one
        // borderline notice with only a plain, non-denylisted link (→ the checked-then-decided beat).
        assertThat(spam).hasSize(3);
        long denylisted = spam.stream()
                .filter(e -> text(e.raw()).contains(NormalMorningScenario.BANK_SCAM_HOST)
                        || text(e.raw()).contains(NormalMorningScenario.PRIZE_SCAM_HOST))
                .count();
        assertThat(denylisted).as("the two flagrant scams carry a denylisted link").isEqualTo(2);

        long borderline = spam.stream()
                .filter(e -> !text(e.raw()).contains(NormalMorningScenario.BANK_SCAM_HOST)
                        && !text(e.raw()).contains(NormalMorningScenario.PRIZE_SCAM_HOST))
                .count();
        assertThat(borderline).as("exactly one borderline notice with no denylisted link").isEqualTo(1);

        for (ScenarioEmail email : spam) {
            assertThat(dmarc(email.raw())).as("scams are unauthenticated").isNotEqualTo("pass");
        }
    }

    @Test
    void it_prewarms_its_three_good_senders_and_leaves_the_scam_senders_cold() {
        List<Scenario.SenderWarmup> warmups = SCENARIO.prewarm();

        // The three good senders are pre-warmed by their exact SenderKey; every scam/borderline sender
        // is deliberately left unseeded so it is (correctly) treated as unknown.
        assertThat(warmups).extracting(Scenario.SenderWarmup::senderKey)
                .containsExactlyInAnyOrder(
                        SenderKey.of("mom@family.example", "family.example"),
                        SenderKey.of("weekly@acme-store.example", "acme-store.example"),
                        SenderKey.of("receipts@rivertown-goods.example", "rivertown-goods.example"));
        assertThat(warmups).allSatisfy(w -> assertThat(w.weight()).isPositive());

        // The warmed keys are exactly the From addresses of the good (LEGIT/NEWSLETTER) mail.
        List<String> goodSenders = SCENARIO.build(1L).stream()
                .filter(e -> e.beat() == Beat.LEGIT || e.beat() == Beat.NEWSLETTER)
                .map(e -> {
                    ParsedEmail md = PARSER.parse(e.raw());
                    return SenderKey.of(md.sender(), md.senderDomain());
                })
                .toList();
        assertThat(warmups).extracting(Scenario.SenderWarmup::senderKey)
                .containsExactlyInAnyOrderElementsOf(goodSenders);
    }

    @Test
    void the_same_seed_reproduces_the_script_byte_for_byte() {
        List<ScenarioEmail> first = SCENARIO.build(42L);
        List<ScenarioEmail> second = SCENARIO.build(42L);

        assertThat(first).hasSameSizeAs(second);
        for (int i = 0; i < first.size(); i++) {
            assertThat(first.get(i).raw()).isEqualTo(second.get(i).raw());
        }
    }

    @Test
    void different_seeds_keep_the_structure_but_vary_the_content() {
        List<ScenarioEmail> a = SCENARIO.build(1L);
        List<ScenarioEmail> b = SCENARIO.build(2L);

        assertThat(a.stream().map(ScenarioEmail::beat).toList())
                .isEqualTo(b.stream().map(ScenarioEmail::beat).toList());
        boolean anyDifferent = false;
        for (int i = 0; i < a.size(); i++) {
            if (!java.util.Arrays.equals(a.get(i).raw(), b.get(i).raw())) {
                anyDifferent = true;
                break;
            }
        }
        assertThat(anyDifferent).isTrue();
    }
}
