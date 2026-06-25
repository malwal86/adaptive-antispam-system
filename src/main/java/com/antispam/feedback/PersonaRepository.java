package com.antispam.feedback;

import com.antispam.common.JsonCodec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Persists and reads the {@code user_personas} catalogue (story 07.01). The {@link PersonaConfig}
 * payload is stored as JSONB, mirroring {@code EmailFeaturesRepository}.
 *
 * <p>{@link #seed} is an idempotent upsert keyed on the content-addressed id: re-seeding the same
 * definition refreshes the row in place rather than creating a duplicate, so "seed on every boot"
 * converges to a fixed catalogue. {@link #findAll} returns personas in name order so the population
 * assembler's allocation is deterministic regardless of insertion order.
 */
@Repository
public class PersonaRepository {

    private static final String UPSERT_SQL = """
            insert into user_personas (id, name, click_bias, report_bias, risk_tolerance, config_json)
            values (?, ?, ?, ?, ?, ?::jsonb)
            on conflict (id)
            do update set name           = excluded.name,
                          click_bias     = excluded.click_bias,
                          report_bias    = excluded.report_bias,
                          risk_tolerance = excluded.risk_tolerance,
                          config_json    = excluded.config_json
            """;

    private static final String SELECT_ALL_SQL = """
            select id, name, click_bias, report_bias, risk_tolerance, config_json
            from user_personas
            order by name
            """;

    private static final String SELECT_BY_NAME_SQL = """
            select id, name, click_bias, report_bias, risk_tolerance, config_json
            from user_personas
            where name = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Autowired
    public PersonaRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Idempotently upserts every persona (one batch); re-seeding the same set is a no-op. */
    public void seed(List<Persona> personas) {
        jdbc.batchUpdate(UPSERT_SQL, personas, personas.size(), (ps, persona) -> {
            ps.setObject(1, persona.id());
            ps.setString(2, persona.name());
            ps.setDouble(3, persona.clickBias());
            ps.setDouble(4, persona.reportBias());
            ps.setDouble(5, persona.riskTolerance());
            ps.setString(6, toJson(persona.config()));
        });
    }

    /** All seeded personas, in name order. */
    public List<Persona> findAll() {
        return jdbc.query(SELECT_ALL_SQL, MAPPER);
    }

    /** The persona with this name, if seeded. */
    public Optional<Persona> findByName(String name) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(SELECT_BY_NAME_SQL, MAPPER, name));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private String toJson(PersonaConfig config) {
        return JsonCodec.serialize(objectMapper, config, "persona config");
    }

    private PersonaConfig fromJson(String json) {
        try {
            return objectMapper.readValue(json, PersonaConfig.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize persona config: " + json, e);
        }
    }

    private final RowMapper<Persona> MAPPER = (rs, rowNum) -> new Persona(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getDouble("click_bias"),
            rs.getDouble("report_bias"),
            rs.getDouble("risk_tolerance"),
            fromJson(rs.getString("config_json")));
}
