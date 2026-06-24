package com.antispam.arena;

import com.antispam.seed.GroundTruthLabel;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Access to the {@code adversarial_emails} lineage table (story 08.01). Append-only by intent: a
 * variant is minted once and logged once, so there is no update or delete. The single-variant-per-
 * email uniqueness lives in the schema; this repository surfaces it as an idempotent save so a
 * re-mint of identical bytes does not create a second lineage row.
 */
@Repository
public class AdversarialEmailRepository {

    private static final String COLUMNS = """
            id, variant_email_id, seed_email_id, parent_variant_id,
            mutation_strategy, ground_truth_label, attacker_model, run_id, generation, created_at
            """;

    private static final String INSERT_SQL = """
            insert into adversarial_emails (
                id, variant_email_id, seed_email_id, parent_variant_id,
                mutation_strategy, ground_truth_label, attacker_model, run_id, generation)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (variant_email_id) do nothing
            returning """ + COLUMNS;

    private static final String SELECT_BY_ID_SQL =
            "select " + COLUMNS + " from adversarial_emails where id = ?";

    private static final String SELECT_BY_SEED_SQL = "select " + COLUMNS + """
             from adversarial_emails where seed_email_id = ?
            order by created_at, id
            """;

    private static final String SELECT_BY_VARIANT_SQL =
            "select " + COLUMNS + " from adversarial_emails where variant_email_id = ?";

    private static final String SELECT_BY_RUN_SQL = "select " + COLUMNS + """
             from adversarial_emails where run_id = ?
            order by generation, created_at, id
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public AdversarialEmailRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Logs a new variant. Idempotent on {@code variantEmailId}: if that email is already recorded as
     * a variant, the existing lineage row is returned unchanged rather than a second one written.
     *
     * @param parentVariantId the parent variant for an iterative attack, or null when mutated
     *                        directly from the seed
     * @param runId           the attack run this variant belongs to (story 08.02), or null for a
     *                        standalone mutation (08.01); travels together with {@code generation}
     * @param generation      the 1-based generation that minted it, or null for a standalone mutation
     */
    public AdversarialEmail save(UUID variantEmailId, UUID seedEmailId, UUID parentVariantId,
            MutationStrategy strategy, GroundTruthLabel label, String attackerModel,
            UUID runId, Integer generation) {
        UUID id = UUID.randomUUID();
        List<AdversarialEmail> inserted = jdbc.query(INSERT_SQL, ADVERSARIAL_EMAIL_MAPPER,
                id, variantEmailId, seedEmailId, parentVariantId,
                strategy.dbValue(), label.dbValue(), attackerModel, runId, generation);
        if (!inserted.isEmpty()) {
            return inserted.get(0);
        }
        // Lost the race / already logged: return the row that owns this variant email.
        return findByVariantEmailId(variantEmailId).orElseThrow(() -> new IllegalStateException(
                "adversarial variant insert conflicted but no existing row found for " + variantEmailId));
    }

    public Optional<AdversarialEmail> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(SELECT_BY_ID_SQL, ADVERSARIAL_EMAIL_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** Every variant descended from {@code seedEmailId}, in mint order — one attack family. */
    public List<AdversarialEmail> findBySeed(UUID seedEmailId) {
        return jdbc.query(SELECT_BY_SEED_SQL, ADVERSARIAL_EMAIL_MAPPER, seedEmailId);
    }

    /** Every variant a run minted, in generation then mint order — replays the campaign (story 08.02). */
    public List<AdversarialEmail> findByRun(UUID runId) {
        return jdbc.query(SELECT_BY_RUN_SQL, ADVERSARIAL_EMAIL_MAPPER, runId);
    }

    private Optional<AdversarialEmail> findByVariantEmailId(UUID variantEmailId) {
        try {
            return Optional.ofNullable(
                    jdbc.queryForObject(SELECT_BY_VARIANT_SQL, ADVERSARIAL_EMAIL_MAPPER, variantEmailId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private static final RowMapper<AdversarialEmail> ADVERSARIAL_EMAIL_MAPPER = (rs, rowNum) -> {
        OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
        return new AdversarialEmail(
                rs.getObject("id", UUID.class),
                rs.getObject("variant_email_id", UUID.class),
                rs.getObject("seed_email_id", UUID.class),
                rs.getObject("parent_variant_id", UUID.class),
                MutationStrategy.fromDbValue(rs.getString("mutation_strategy")),
                GroundTruthLabel.fromDbValue(rs.getString("ground_truth_label")),
                rs.getString("attacker_model"),
                rs.getObject("run_id", UUID.class),
                rs.getObject("generation", Integer.class),
                createdAt == null ? null : createdAt.toInstant());
    };
}
