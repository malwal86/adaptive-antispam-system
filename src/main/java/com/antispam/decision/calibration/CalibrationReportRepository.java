package com.antispam.decision.calibration;

import com.antispam.common.JsonCodec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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
 * Append-only persistence of {@link ReliabilityReport}s with their gate outcome (story
 * 04.02 AC 3). Each calibration run writes one row; the latest row for a model version is
 * the current evidence. The reliability curve is stored as {@code jsonb} so the whole
 * diagram round-trips as one column rather than a child table — it is read back whole or
 * not at all.
 */
@Repository
public class CalibrationReportRepository {

    /**
     * A persisted report: the measured reliability plus the gate it was judged against.
     *
     * @param id        the row id
     * @param report    the reliability measurement
     * @param maxEce    the ceiling the run was judged against
     * @param passed    whether it passed the gate
     * @param createdAt when the run was recorded
     */
    public record StoredReport(UUID id, ReliabilityReport report, double maxEce, boolean passed,
            Instant createdAt) {
    }

    private static final String INSERT_SQL = """
            insert into model_calibration_reports (
                id, model_version, method, sample_count, bin_count,
                ece_raw, ece_calibrated, max_ece_threshold, passed, reliability_bins)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
            returning created_at
            """;

    private static final String SELECT_LATEST_SQL = """
            select id, model_version, method, sample_count, bin_count,
                   ece_raw, ece_calibrated, max_ece_threshold, passed, reliability_bins, created_at
            from model_calibration_reports
            where model_version = ?
            order by created_at desc
            limit 1
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    @Autowired
    public CalibrationReportRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Persists a run and returns it with its generated id and timestamp. */
    public StoredReport save(ReliabilityReport report, double maxEce, boolean passed) {
        UUID id = UUID.randomUUID();
        String binsJson = writeBins(report.calibratedBins());
        OffsetDateTime createdAt = jdbc.queryForObject(INSERT_SQL,
                (rs, rowNum) -> rs.getObject("created_at", OffsetDateTime.class),
                id, report.modelVersion(), report.method(), report.sampleCount(), report.binCount(),
                report.eceRaw(), report.eceCalibrated(), maxEce, passed, binsJson);
        return new StoredReport(id, report, maxEce, passed, createdAt.toInstant());
    }

    /** The most recent run for {@code modelVersion}, or empty if none has been recorded. */
    public Optional<StoredReport> findLatest(String modelVersion) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(SELECT_LATEST_SQL, ROW_MAPPER, modelVersion));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private String writeBins(List<ReliabilityBin> bins) {
        return JsonCodec.serialize(json, bins, "reliability bins");
    }

    private List<ReliabilityBin> readBins(String binsJson) {
        try {
            return List.of(json.readValue(binsJson, ReliabilityBin[].class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to read reliability bins", e);
        }
    }

    private final RowMapper<StoredReport> ROW_MAPPER = (rs, rowNum) -> {
        ReliabilityReport report = new ReliabilityReport(
                rs.getString("model_version"),
                rs.getString("method"),
                rs.getInt("sample_count"),
                rs.getInt("bin_count"),
                rs.getDouble("ece_raw"),
                rs.getDouble("ece_calibrated"),
                readBins(rs.getString("reliability_bins")));
        OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
        return new StoredReport(
                rs.getObject("id", UUID.class),
                report,
                rs.getDouble("max_ece_threshold"),
                rs.getBoolean("passed"),
                createdAt.toInstant());
    };
}
