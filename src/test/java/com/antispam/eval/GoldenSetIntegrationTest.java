package com.antispam.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.experiment.replay.LabeledReplayDecision;
import com.antispam.experiment.replay.ReplayDecisionRepository;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import com.antispam.seed.GroundTruthLabel;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The frozen golden + rolling fresh sets against a real Postgres (story 11.02). Proves the four things
 * the unit tests cannot: a freeze snapshots exactly the current eval side, the database refuses to
 * change a frozen version, the fresh set grows without touching golden, and the gate's version-pinned
 * grading reads only that version's members — the version-comparable basis for the precision floor.
 *
 * <p>The suite shares one immutable {@code emails} table and a global split, so each test freezes its
 * OWN uniquely-named version over emails it directly assigns to the eval side, and asserts only about
 * those — never about the global set's size, which other tests perturb.
 */
class GoldenSetIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private IngestService ingestService;

    @Autowired
    private EvalSetService evalSets;

    @Autowired
    private GoldenSetRepository goldenSets;

    @Autowired
    private ReplayDecisionRepository replayDecisions;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID ingestEvalLabeled(String tag, GroundTruthLabel label) {
        IngestResult ingested = ingestService.ingest(
                ("From: " + tag + "@golden.test\nSubject: hi\n\n" + tag + " " + UUID.randomUUID())
                        .getBytes(StandardCharsets.UTF_8), "api");
        jdbc.update("insert into ground_truth_labels (email_id, label, dataset_source) "
                + "values (?, ?, 'test') on conflict (email_id) do nothing", ingested.emailId(), label.dbValue());
        jdbc.update("""
                insert into eval_split_assignments (email_id, split_side, group_key)
                values (?, 'eval', ?)
                on conflict (email_id) do update set split_side = 'eval'
                """, ingested.emailId(), tag);
        return ingested.emailId();
    }

    private Set<UUID> goldenMemberIds(String version) {
        return jdbc.queryForList(
                "select email_id from golden_set_members where version = ?", UUID.class, version)
                .stream().collect(Collectors.toSet());
    }

    @Test
    void freezing_snapshots_the_current_eval_side_and_is_byte_stable_across_versions() {
        UUID spam = ingestEvalLabeled("gold-snap-spam", GroundTruthLabel.SPAM);
        UUID ham = ingestEvalLabeled("gold-snap-ham", GroundTruthLabel.HAM);

        String v1 = "golden-snap-" + UUID.randomUUID();
        GoldenSetVersion frozen = evalSets.freezeGolden(v1);

        assertThat(goldenMemberIds(v1)).contains(spam, ham);
        assertThat(frozen.evalFraction()).isEqualTo(0.2);
        assertThat(frozen.seed()).isEqualTo(42L);

        // A second version frozen from the same eval side captures the same emails — the snapshot is a
        // pure function of the eval side, the property version-comparability rests on.
        String v2 = "golden-snap2-" + UUID.randomUUID();
        evalSets.freezeGolden(v2);
        assertThat(goldenMemberIds(v2)).containsExactlyInAnyOrderElementsOf(goldenMemberIds(v1));
    }

    @Test
    void a_frozen_version_cannot_be_mutated_or_redefined() {
        UUID member = ingestEvalLabeled("gold-frozen", GroundTruthLabel.SPAM);
        String version = "golden-frozen-" + UUID.randomUUID();
        evalSets.freezeGolden(version);

        // The database refuses to delete or update a frozen member (V32 triggers) ...
        assertThatThrownBy(() -> jdbc.update(
                "delete from golden_set_members where version = ?", version))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "update golden_set_versions set member_count = 0 where version = ?", version))
                .isInstanceOf(DataAccessException.class);
        // ... and the service refuses to redefine the label.
        assertThatThrownBy(() -> evalSets.freezeGolden(version))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already frozen");

        // The member survived every rejected mutation.
        assertThat(goldenMemberIds(version)).contains(member);
    }

    @Test
    void a_fresh_attack_lands_only_in_the_fresh_set_never_in_a_frozen_golden_version() {
        String version = "golden-isolation-" + UUID.randomUUID();
        evalSets.freezeGolden(version);
        Set<UUID> goldenBefore = goldenMemberIds(version);

        UUID reported = ingestEvalLabeled("fresh-attack", GroundTruthLabel.PHISH);
        evalSets.addFreshChallenge(reported, GroundTruthLabel.PHISH, "reported");

        // It is in the fresh set ...
        Long inFresh = jdbc.queryForObject(
                "select count(*) from fresh_challenge_members where email_id = ?", Long.class, reported);
        assertThat(inFresh).isEqualTo(1L);
        // ... and the frozen golden version is byte-for-byte unchanged.
        assertThat(goldenMemberIds(version)).isEqualTo(goldenBefore).doesNotContain(reported);
    }

    @Test
    void the_gate_grading_query_reads_only_the_named_versions_members() {
        UUID inVersion = ingestEvalLabeled("gate-in", GroundTruthLabel.SPAM);
        String version = "golden-gate-" + UUID.randomUUID();
        evalSets.freezeGolden(version);
        // An eval-side email labeled AFTER the freeze is not in this frozen version.
        UUID afterFreeze = ingestEvalLabeled("gate-after", GroundTruthLabel.HAM);

        // A run that replayed BOTH emails: the version-pinned grading must see only the frozen member.
        UUID runId = UUID.randomUUID();
        insertReplayDecision(runId, inVersion);
        insertReplayDecision(runId, afterFreeze);

        Set<UUID> graded = replayDecisions.findLabeledByRunIdInGoldenVersion(runId, version).stream()
                .map(LabeledReplayDecision::emailId).collect(Collectors.toSet());

        assertThat(graded).contains(inVersion).doesNotContain(afterFreeze);
    }

    private void insertReplayDecision(UUID runId, UUID emailId) {
        jdbc.update("""
                insert into replay_decisions (
                    id, run_id, policy_version, email_id, decision, route_used,
                    reason_codes, routing_reasons)
                values (?, ?, 'cand', ?, 'BLOCK', 'MODEL', '{}'::text[], '{}'::text[])
                """, UUID.randomUUID(), runId, emailId);
    }
}
