package com.antispam.reputation.accrual;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Makes hot-sender partition skew observable (story 03.05 AC 3). Per-sender
 * reputation serialization is bought by partitioning {@code emails.raw} by sender;
 * the documented, accepted demo-scale cost is that one prolific sender's traffic all
 * lands on a single partition, so that partition's consumer does disproportionately
 * more work while others idle. There is no correctness loss — the append-only log
 * and per-partition ordering still hold — only a throughput imbalance, and this
 * meter surfaces it rather than letting it hide.
 *
 * <p>It exposes two views, both updated on every accrual:
 * <ul>
 *   <li>a counter {@code antispam.reputation.accrual.processed} tagged by
 *       {@code partition}, so per-partition throughput is queryable directly;</li>
 *   <li>a gauge {@code antispam.reputation.accrual.partition.skew} equal to
 *       {@code max / mean} of the per-partition counts — {@code 1.0} when traffic is
 *       perfectly balanced and larger the more one partition dominates.</li>
 * </ul>
 *
 * <p>Counts are this instance's view (the consumers running in this JVM); at demo
 * scale the consumer group's partitions are owned here, so that is the whole picture.
 */
@Component
public class PartitionSkewMeter {

    /** Counter name; tagged {@code partition} — one series per partition this instance owns. */
    static final String PROCESSED_COUNTER = "antispam.reputation.accrual.processed";

    /** Gauge name; {@code max/mean} of per-partition accrual counts (1.0 == balanced). */
    static final String SKEW_GAUGE = "antispam.reputation.accrual.partition.skew";

    private final MeterRegistry meters;
    private final ConcurrentMap<Integer, AtomicLong> countsByPartition = new ConcurrentHashMap<>();

    @Autowired
    public PartitionSkewMeter(MeterRegistry meters) {
        this.meters = meters;
        Gauge.builder(SKEW_GAUGE, this, PartitionSkewMeter::skew)
                .description("max/mean of per-partition reputation-accrual counts; 1.0 is balanced")
                .register(meters);
    }

    /** Records one accrual handled on {@code partition}, updating both the counter and the skew. */
    public void record(int partition) {
        countsByPartition.computeIfAbsent(partition, p -> new AtomicLong()).incrementAndGet();
        meters.counter(PROCESSED_COUNTER, "partition", Integer.toString(partition)).increment();
    }

    /**
     * The current skew: {@code max / mean} of the per-partition accrual counts. Returns
     * the balanced floor {@code 1.0} when no traffic has been seen or a single partition
     * has — there is nothing to be skewed against — and never divides by zero (every
     * recorded partition contributes at least one).
     */
    public double skew() {
        long max = 0;
        long sum = 0;
        int partitions = 0;
        for (AtomicLong count : countsByPartition.values()) {
            long c = count.get();
            max = Math.max(max, c);
            sum += c;
            partitions++;
        }
        if (partitions == 0) {
            return 1.0;
        }
        double mean = (double) sum / partitions;
        return max / mean;
    }
}
