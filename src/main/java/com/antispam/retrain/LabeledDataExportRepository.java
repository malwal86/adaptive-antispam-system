package com.antispam.retrain;

import com.antispam.event.SenderKey;
import com.antispam.seed.GroundTruthLabel;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reads the labeled training corpus out of Postgres for the retrain export (story 10.01, PRD §Subsystem
 * 9 step 1), already filtered to exclude anything on the golden eval side so no held-out example can
 * leak into training. The two sources are read separately because they live in different tables and
 * carry weight/provenance differently:
 *
 * <ul>
 *   <li><b>Seed labels</b> ({@code ground_truth_labels}) are high-confidence ground truth — weight
 *       {@value #SEED_LABEL_WEIGHT} — restricted to the {@code train} side of the materialized split
 *       (story 11.01/11.03). The eval side of this table <em>is</em> the golden judging set, so it is
 *       excluded by construction.</li>
 *   <li><b>Feedback + arena labels</b> ({@code retrain_labels}, stories 07.03 / 08.04) carry their own
 *       gate/corpus weight and JSON provenance. These are never assigned to eval (feedback trains but
 *       never judges — story 11.03), so they have no split row; a {@code left join} keeps them while a
 *       {@code split_side = 'eval'} match (a label on a seed email that landed in the holdout) is
 *       dropped — leakage-proof through the feedback path too.</li>
 * </ul>
 *
 * <p>Both queries end in a total order with a unique tiebreaker, so the same DB snapshot always yields
 * the same rows in the same order (the export is reproducible — story 10.01 AC 5).
 */
@Repository
public class LabeledDataExportRepository {

    /** Seed ground truth is full-confidence, so it enters training at weight 1.0. */
    static final double SEED_LABEL_WEIGHT = 1.0;

    // Seed labels on the TRAIN side only: the eval side of ground_truth_labels is the golden judging set
    // (V10), so an inner join on split_side = 'train' both excludes the holdout and skips any seed label
    // not yet assigned to a split (it enters training once the split is rebuilt — never as a leak).
    private static final String SEED_SQL = """
            select g.email_id                                            as email_id,
                   g.label                                               as label,
                   %s                                                    as weight,
                   'seed'                                                as source,
                   json_build_object('datasetSource', g.dataset_source)::text as provenance,
                   e.sender                                              as sender,
                   e.sender_domain                                       as sender_domain
            from ground_truth_labels g
            join eval_split_assignments a on a.email_id = g.email_id
            join emails e on e.id = g.email_id
            where a.split_side = 'train'
            order by g.email_id
            """.formatted(SEED_LABEL_WEIGHT);

    // Weighted feedback (07.03) + arena (08.04) labels, carrying their own weight and JSON provenance.
    // left join + "split_side is distinct from 'eval'" keeps unassigned rows (the common case — these
    // are never in eval) while dropping any whose email landed on the golden side.
    private static final String RETRAIN_SQL = """
            select r.email_id      as email_id,
                   r.label         as label,
                   r.weight        as weight,
                   r.source        as source,
                   r.provenance    as provenance,
                   e.sender        as sender,
                   e.sender_domain as sender_domain
            from retrain_labels r
            join emails e on e.id = r.email_id
            left join eval_split_assignments a on a.email_id = r.email_id
            where a.split_side is distinct from 'eval'
            order by r.source, r.email_id, r.id
            """;

    private final JdbcTemplate jdbc;
    private final ExportDeidentifier deidentifier;

    @Autowired
    public LabeledDataExportRepository(JdbcTemplate jdbc, ExportDeidentifier deidentifier) {
        this.jdbc = jdbc;
        this.deidentifier = deidentifier;
    }

    /** The train-side seed labels, oldest-id first, each stamped with {@code featureVersion}. */
    public List<TrainingExample> exportSeedLabels(int featureVersion) {
        return jdbc.query(SEED_SQL, mapper(featureVersion));
    }

    /** The non-eval feedback and arena labels, ordered by source then email, each stamped with {@code featureVersion}. */
    public List<TrainingExample> exportFeedbackAndArenaLabels(int featureVersion) {
        return jdbc.query(RETRAIN_SQL, mapper(featureVersion));
    }

    /**
     * Maps a row to a de-identified example (story 14.04): the sender is read only to derive its
     * stable pseudonym — it never lands in the {@link TrainingExample} — and the provenance is
     * sanitized before it leaves the store.
     */
    private RowMapper<TrainingExample> mapper(int featureVersion) {
        return (rs, rowNum) -> {
            String senderKey = SenderKey.of(rs.getString("sender"), rs.getString("sender_domain"));
            return new TrainingExample(
                    rs.getObject("email_id", UUID.class),
                    GroundTruthLabel.fromDbValue(rs.getString("label")),
                    rs.getDouble("weight"),
                    rs.getString("source"),
                    deidentifier.sanitizeProvenance(rs.getString("provenance")),
                    featureVersion,
                    deidentifier.pseudonymFor(senderKey));
        };
    }
}
