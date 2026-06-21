package com.antispam.reputation.accrual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * The partition-skew meter in isolation (story 03.05 AC 3). Per-sender
 * serialization is bought by partitioning, and the documented cost is hot-sender
 * partition skew: one sender's burst lands wholly on one partition while others sit
 * idle. This meter makes that cost observable — a per-partition processed counter
 * plus a {@code max/mean} skew gauge where {@code 1.0} is perfectly balanced and a
 * larger value means one partition is carrying disproportionately more.
 */
class PartitionSkewMeterTest {

    @Test
    void perfectly_balanced_partitions_have_skew_of_one() {
        PartitionSkewMeter meter = new PartitionSkewMeter(new SimpleMeterRegistry());

        meter.record(0);
        meter.record(1);
        meter.record(2);

        assertThat(meter.skew()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void a_hot_partition_raises_the_skew_above_one() {
        PartitionSkewMeter meter = new PartitionSkewMeter(new SimpleMeterRegistry());

        // Partition 0 is hot (7 records); 1 and 2 see one each. mean = 3, max = 7.
        for (int i = 0; i < 7; i++) {
            meter.record(0);
        }
        meter.record(1);
        meter.record(2);

        assertThat(meter.skew()).isCloseTo(7.0 / 3.0, within(1e-9));
    }

    @Test
    void a_single_observed_partition_reports_no_skew() {
        // With one partition seen there is nothing to be skewed against: max == mean.
        PartitionSkewMeter meter = new PartitionSkewMeter(new SimpleMeterRegistry());

        meter.record(4);
        meter.record(4);

        assertThat(meter.skew()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void before_any_record_skew_is_one() {
        // No traffic is balanced by definition — never a divide-by-zero.
        PartitionSkewMeter meter = new PartitionSkewMeter(new SimpleMeterRegistry());

        assertThat(meter.skew()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void the_skew_gauge_is_registered_and_tracks_recorded_traffic() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PartitionSkewMeter meter = new PartitionSkewMeter(registry);

        for (int i = 0; i < 5; i++) {
            meter.record(0);
        }
        meter.record(1);

        Double gauge = registry.get("antispam.reputation.accrual.partition.skew").gauge().value();
        assertThat(gauge).isCloseTo(5.0 / 3.0, within(1e-9));
    }

    @Test
    void each_partition_increments_its_own_processed_counter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PartitionSkewMeter meter = new PartitionSkewMeter(registry);

        meter.record(0);
        meter.record(0);
        meter.record(1);

        assertThat(registry.get("antispam.reputation.accrual.processed")
                .tag("partition", "0").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("antispam.reputation.accrual.processed")
                .tag("partition", "1").counter().count()).isEqualTo(1.0);
    }
}
