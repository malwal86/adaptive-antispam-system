package com.antispam.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.features.EmailFeatureExtractor;
import com.antispam.ingest.EmailParser;
import com.antispam.ingest.ParsedEmail;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The "a normal morning" scenario's pure, seeded core: routine inbox triage where authenticated legit
 * mail and a newsletter are benign while two unauthenticated junk messages are the clear-cut blocks.
 * These assert the structural contract the calm demo depends on — the right beats, the right
 * authentication, benign vs. spammy content, distinct provenance, and reproducibility from the seed —
 * leaving the live-pipeline verdicts to the scenario runner and the model.
 */
class NormalMorningScenarioTest {

    private static final EmailParser PARSER = new EmailParser();
    private static final NormalMorningScenario SCENARIO = new NormalMorningScenario();

    private static String dmarc(byte[] raw) {
        ParsedEmail md = PARSER.parse(raw);
        return EmailFeatureExtractor.authFeatures(md.authResults()).dmarc();
    }

    private static boolean hasIpLink(byte[] raw) {
        return EmailFeatureExtractor.linkFeatures(new String(raw, StandardCharsets.UTF_8)).hasIpUrl();
    }

    @Test
    void it_is_named_for_the_api_path_and_picker() {
        assertThat(SCENARIO.name()).isEqualTo("a_normal_morning");
    }

    @Test
    void a_morning_is_a_mix_of_legit_newsletter_and_obvious_spam() {
        List<ScenarioEmail> script = SCENARIO.build(1L);

        assertThat(script.stream().map(ScenarioEmail::beat))
                .containsExactly(Beat.LEGIT, Beat.NEWSLETTER, Beat.LEGIT, Beat.SPAM, Beat.SPAM);
        // Each email is tagged with this scenario's own provenance, not the thunderclap's.
        assertThat(script).allSatisfy(e -> assertThat(e.source()).startsWith("normal-morning-"));
    }

    @Test
    void every_email_is_a_parseable_message() {
        for (ScenarioEmail email : SCENARIO.build(7L)) {
            ParsedEmail md = PARSER.parse(email.raw());
            assertThat(md.sender()).as("sender parses").isNotNull();
            assertThat(md.senderDomain()).as("domain parses").isNotBlank();
        }
    }

    @Test
    void legit_and_newsletter_mail_is_authenticated_and_benign() {
        List<ScenarioEmail> benign = SCENARIO.build(3L).stream()
                .filter(e -> e.beat() == Beat.LEGIT || e.beat() == Beat.NEWSLETTER)
                .toList();

        assertThat(benign).isNotEmpty();
        for (ScenarioEmail email : benign) {
            assertThat(dmarc(email.raw())).as("authenticated").isEqualTo("pass");
            // No raw-IP credential-harvest links in the good mail — benign content the gate lets through.
            assertThat(hasIpLink(email.raw())).as("no raw-IP link").isFalse();
        }
    }

    @Test
    void the_spam_is_unauthenticated_junk_with_a_bait_link() {
        List<ScenarioEmail> spam = SCENARIO.build(3L).stream()
                .filter(e -> e.beat() == Beat.SPAM)
                .toList();

        assertThat(spam).hasSize(2);
        for (ScenarioEmail email : spam) {
            // Unknown, unauthenticated senders with a shouty bait link — blocked on content, not auth.
            assertThat(dmarc(email.raw())).isNotEqualTo("pass");
            assertThat(hasIpLink(email.raw())).as("bait link").isTrue();
        }
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
