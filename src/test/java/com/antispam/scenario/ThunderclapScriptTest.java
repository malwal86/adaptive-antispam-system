package com.antispam.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.antispam.features.EmailFeatureExtractor;
import com.antispam.ingest.EmailParser;
import com.antispam.ingest.ParsedEmail;
import com.antispam.scenario.ThunderclapScript.Plan;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The thunderclap scenario's testable core (story 12.05): {@link ThunderclapScript} is a pure,
 * seeded builder, so its output can be pinned down completely without standing up Spring, a database,
 * or the model. These tests assert the structural contract every demo beat depends on — the right
 * senders, the right authentication, near-duplicate-but-mutated phishing, and exact reproducibility
 * from the seed — leaving the runner's job (feeding this script through the live pipeline) to the
 * integration test.
 */
class ThunderclapScriptTest {

    private static final EmailParser PARSER = new EmailParser();
    private static final Plan PLAN = new Plan(6, 5, 3);

    private static String dmarc(byte[] raw) {
        ParsedEmail md = PARSER.parse(raw);
        return EmailFeatureExtractor.authFeatures(md.authResults()).dmarc();
    }

    private static String body(byte[] raw) {
        return EmailFeatureExtractor.displayText(raw);
    }

    private static int urlCount(byte[] raw) {
        // The feature extractor reads links from the raw (markup-preserving) body.
        return EmailFeatureExtractor.linkFeatures(new String(raw, StandardCharsets.UTF_8)).urlCount();
    }

    @Test
    void builds_the_planned_number_of_emails_in_beat_order() {
        List<ScenarioEmail> script = ThunderclapScript.build(1L, PLAN);

        // warm-ups, then the attack campaign, then one spoof, then the misconfigured-legit sender.
        assertThat(script).hasSize(6 + 5 + 1 + 3);
        assertThat(script.stream().map(ScenarioEmail::beat))
                .containsExactly(
                        Beat.WARMUP, Beat.WARMUP, Beat.WARMUP, Beat.WARMUP, Beat.WARMUP, Beat.WARMUP,
                        Beat.ATTACK, Beat.ATTACK, Beat.ATTACK, Beat.ATTACK, Beat.ATTACK,
                        Beat.SPOOF,
                        Beat.MISCONFIGURED_LEGIT, Beat.MISCONFIGURED_LEGIT, Beat.MISCONFIGURED_LEGIT);
    }

    @Test
    void every_email_is_a_parseable_message_tagged_with_its_beats_source() {
        for (ScenarioEmail email : ThunderclapScript.build(7L, PLAN)) {
            ParsedEmail md = PARSER.parse(email.raw());
            assertThat(md.sender()).as("sender parses").isNotNull();
            assertThat(md.senderDomain()).as("domain parses").isNotBlank();
            assertThat(email.source()).isEqualTo(email.beat().source());
        }
    }

    @Test
    void warm_ups_are_authenticated_benign_mail_from_the_hero_domain() {
        List<ScenarioEmail> warmUps = beat(ThunderclapScript.build(3L, PLAN), Beat.WARMUP);

        assertThat(warmUps).isNotEmpty();
        for (ScenarioEmail email : warmUps) {
            assertThat(PARSER.parse(email.raw()).senderDomain()).isEqualTo(ThunderclapScript.WARM_DOMAIN);
            assertThat(dmarc(email.raw())).isEqualTo("pass");
            // Benign: no credential-harvest link in the warm-up traffic.
            assertThat(urlCount(email.raw())).isZero();
        }
    }

    @Test
    void the_attack_campaign_is_mutated_phishing_from_the_same_warmed_domain() {
        List<ScenarioEmail> attack = beat(ThunderclapScript.build(3L, PLAN), Beat.ATTACK);

        assertThat(attack).hasSizeGreaterThan(1);
        for (ScenarioEmail email : attack) {
            // The warmed sender turning bad: same domain, still DMARC-aligned, so the curve that rose
            // is the one that collapses — and a phishing link is present in every variant.
            assertThat(PARSER.parse(email.raw()).senderDomain()).isEqualTo(ThunderclapScript.WARM_DOMAIN);
            assertThat(dmarc(email.raw())).isEqualTo("pass");
            assertThat(urlCount(email.raw())).isGreaterThanOrEqualTo(1);
        }
        // Mutated, not cloned: the bodies are near-duplicates (a campaign) yet not all identical.
        long distinctBodies = attack.stream().map(e -> body(e.raw())).distinct().count();
        assertThat(distinctBodies).isGreaterThan(1);
    }

    @Test
    void the_spoof_impersonates_the_warm_domain_but_fails_authentication() {
        ScenarioEmail spoof = beat(ThunderclapScript.build(3L, PLAN), Beat.SPOOF).get(0);

        // Same domain it impersonates, but DMARC does not pass — auth gating (03.03) denies it the
        // warmed domain's earned trust, so it gets nothing.
        assertThat(PARSER.parse(spoof.raw()).senderDomain()).isEqualTo(ThunderclapScript.WARM_DOMAIN);
        assertThat(dmarc(spoof.raw())).isNotEqualTo("pass");
    }

    @Test
    void the_misconfigured_legit_sender_sends_benign_mail_with_broken_auth() {
        List<ScenarioEmail> legit = beat(ThunderclapScript.build(3L, PLAN), Beat.MISCONFIGURED_LEGIT);

        assertThat(legit).isNotEmpty();
        for (ScenarioEmail email : legit) {
            // A different, genuinely legitimate sender whose auth is broken; benign content earns trust
            // slowly through the soft gate.
            assertThat(PARSER.parse(email.raw()).senderDomain()).isEqualTo(ThunderclapScript.LEGIT_DOMAIN);
            assertThat(dmarc(email.raw())).isNotEqualTo("pass");
            assertThat(urlCount(email.raw())).isZero();
        }
    }

    @Test
    void the_same_seed_reproduces_the_script_byte_for_byte() {
        List<ScenarioEmail> first = ThunderclapScript.build(42L, PLAN);
        List<ScenarioEmail> second = ThunderclapScript.build(42L, PLAN);

        assertThat(first).hasSameSizeAs(second);
        for (int i = 0; i < first.size(); i++) {
            assertThat(first.get(i).beat()).isEqualTo(second.get(i).beat());
            assertThat(first.get(i).raw()).isEqualTo(second.get(i).raw());
        }
    }

    @Test
    void different_seeds_keep_the_structure_but_vary_the_content() {
        List<ScenarioEmail> a = ThunderclapScript.build(1L, PLAN);
        List<ScenarioEmail> b = ThunderclapScript.build(2L, PLAN);

        // Same beats and counts (the demo always lands the same moments)...
        assertThat(a.stream().map(ScenarioEmail::beat).toList())
                .isEqualTo(b.stream().map(ScenarioEmail::beat).toList());
        // ...but the concrete mail differs somewhere (seeded mutation), so re-runs aren't identical.
        boolean anyDifferent = false;
        for (int i = 0; i < a.size(); i++) {
            if (!java.util.Arrays.equals(a.get(i).raw(), b.get(i).raw())) {
                anyDifferent = true;
                break;
            }
        }
        assertThat(anyDifferent).isTrue();
    }

    @Test
    void the_default_plan_is_used_when_none_is_given() {
        assertThat(ThunderclapScript.build(1L)).hasSameSizeAs(ThunderclapScript.build(1L, Plan.DEFAULT));
    }

    @Test
    void a_plan_must_have_at_least_one_of_each_phase() {
        assertThatThrownBy(() -> new Plan(0, 5, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Plan(6, 1, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Plan(6, 5, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static List<ScenarioEmail> beat(List<ScenarioEmail> script, Beat beat) {
        return script.stream().filter(e -> e.beat() == beat).toList();
    }
}
