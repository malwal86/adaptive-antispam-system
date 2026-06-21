package com.antispam.reputation;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.OptionalDouble;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence for sender reputation across its two tables: the append-only
 * {@code reputation_events} log (the source of truth) and the {@code senders}
 * materialized-score cache. They are one cohesive concern — the cache is nothing but
 * a projection of the log — so a single repository owns both, keeping the
 * truth-vs-cache relationship in one place.
 *
 * <p>{@link #append} only ever inserts (the table rejects updates/deletes), so the
 * log grows monotonically and a sender's score is always re-derivable from it via
 * {@link #countsFor}. {@link #saveScore} refreshes the cache; it is an upsert, never
 * the authority.
 */
@Repository
public class ReputationRepository {

    private static final String APPEND_SQL = """
            insert into reputation_events (sender_key, signal, weight, decay_factor, source)
            values (?, ?, ?, ?, ?)
            """;

    // Decayed good/bad totals in one pass at the read instant (story 03.02). Each
    // event's weight is scaled by 0.5 ^ (age / halfLife) -- the same exponential factor
    // as ExponentialDecay, mirrored here in SQL so the aggregation stays a single scan
    // rather than loading every event into the JVM. age = readAt - occurred_at in
    // seconds (extract(epoch ...)); GREATEST(0, ...) clamps a negative age (an event
    // newer than the read instant under clock skew) to full weight, never amplifying,
    // matching ExponentialDecay. FILTER sums each bucket; COALESCE turns "no rows" into
    // 0 rather than NULL, so an unseen sender reads as (0, 0) -- the pure prior -- with
    // no special-casing above. The first ? is readAt, the second the half-life seconds.
    private static final String COUNTS_SQL = """
            select
                coalesce(sum(decayed_weight) filter (where signal = 'GOOD'), 0) as good,
                coalesce(sum(decayed_weight) filter (where signal = 'BAD'), 0)  as bad
            from (
                select signal,
                       weight * power(0.5, greatest(0, extract(epoch from (? - occurred_at))) / ?)
                           as decayed_weight
                from reputation_events
                where sender_key = ?
            ) decayed_events
            """;

    private static final String SAVE_SCORE_SQL = """
            insert into senders (sender_key, current_reputation_score, score_updated_at)
            values (?, ?, now())
            on conflict (sender_key)
            do update set current_reputation_score = excluded.current_reputation_score,
                          score_updated_at = excluded.score_updated_at
            """;

    private static final String FIND_SCORE_SQL =
            "select current_reputation_score from senders where sender_key = ?";

    private final JdbcTemplate jdbc;

    @Autowired
    public ReputationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Appends one signal to the log. The database assigns {@code id} and {@code occurred_at}. */
    public void append(ReputationEvent event) {
        jdbc.update(APPEND_SQL,
                event.senderKey(),
                event.signal().name(),
                event.weight(),
                event.decayFactor(),
                event.source());
    }

    /**
     * Sums the sender's good/bad weight from the log as of {@code readAt}, with each
     * event decayed by {@code decay} according to its age — the recompute-from-truth
     * that makes the score auditable and lets trust fade lazily at read time (story
     * 03.02). Returns {@code (0, 0)} for a sender with no events.
     *
     * @param senderKey the sender to total
     * @param readAt    the instant the score is read as of; each event's age is
     *                  measured back from here
     * @param decay     the read-time decay model (its half-life drives the fade)
     */
    public ReputationCounts countsFor(String senderKey, Instant readAt, ExponentialDecay decay) {
        return jdbc.queryForObject(COUNTS_SQL,
                (rs, rowNum) -> new ReputationCounts(rs.getDouble("good"), rs.getDouble("bad")),
                OffsetDateTime.ofInstant(readAt, ZoneOffset.UTC),
                decay.halfLifeSeconds(),
                senderKey);
    }

    /** Upserts the sender's cached reputation mean (refreshed on every recorded signal). */
    public void saveScore(String senderKey, double score) {
        jdbc.update(SAVE_SCORE_SQL, senderKey, score);
    }

    /**
     * The cached score for a sender, if one has been materialized. Empty when the
     * sender has no row yet or its score is null — callers recompute from the log
     * rather than trust the cache as authority (story 03.01 AC 5).
     */
    public OptionalDouble findCachedScore(String senderKey) {
        try {
            Double score = jdbc.queryForObject(FIND_SCORE_SQL, (rs, rowNum) -> {
                double value = rs.getDouble("current_reputation_score");
                return rs.wasNull() ? null : value;
            }, senderKey);
            return score == null ? OptionalDouble.empty() : OptionalDouble.of(score);
        } catch (EmptyResultDataAccessException e) {
            return OptionalDouble.empty();
        }
    }
}
