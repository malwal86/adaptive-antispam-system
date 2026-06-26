package com.antispam.privacy.reveal;

import com.antispam.common.JdbcTimestamps;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Append-only access to the {@code reveal_access_audit} log (story 14.05). Every
 * authorized reveal/raw/erasure writes one row here, so unmasked PII access is always
 * attributable after the fact.
 */
@Repository
public class RevealAccessAuditRepository {

    private static final String INSERT_SQL = """
            insert into reveal_access_audit (id, email_id, actor, access_type)
            values (?, ?, ?, ?)
            """;

    private static final String SELECT_BY_EMAIL_SQL = """
            select id, email_id, actor, access_type, at
            from reveal_access_audit where email_id = ?
            order by at desc, id
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public RevealAccessAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Records one authorized access. */
    public void record(UUID emailId, String actor, RevealAccessType type) {
        jdbc.update(INSERT_SQL, UUID.randomUUID(), emailId, actor, type.dbValue());
    }

    /** Every recorded access to one email, newest first — the "who saw this?" read. */
    public List<RevealAccessAudit> findByEmail(UUID emailId) {
        return jdbc.query(SELECT_BY_EMAIL_SQL, AUDIT_MAPPER, emailId);
    }

    private static final RowMapper<RevealAccessAudit> AUDIT_MAPPER = (rs, rowNum) -> new RevealAccessAudit(
            rs.getObject("id", UUID.class),
            rs.getObject("email_id", UUID.class),
            rs.getString("actor"),
            rs.getString("access_type"),
            JdbcTimestamps.instantOrNull(rs, "at"));
}
