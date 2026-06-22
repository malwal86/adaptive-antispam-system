package com.antispam.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.antispam.seed.GroundTruthLabel;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The splitter is where eval integrity lives, so it is tested as a pure unit with
 * hand-built corpora: grouping keeps families whole, the holdout runs time-forward,
 * the same corpus and seed reproduce exactly, and the audit reports leakage honestly
 * — including the residual time overlap an indivisible family can force.
 */
class GroupedTimeForwardSplitterTest {

    private final GroupedTimeForwardSplitter splitter = new GroupedTimeForwardSplitter();

    private static Instant day(int d) {
        return Instant.EPOCH.plus(Duration.ofDays(d));
    }

    private static SplitItem item(String group, int day, GroundTruthLabel label) {
        return new SplitItem(UUID.randomUUID(), group, day(day), label);
    }

    @Test
    void assigns_an_entire_family_to_one_side_never_splitting_it() {
        // A family of five reworded variants spread across time. Whatever side it
        // lands on, all five must move together.
        List<SplitItem> family = new ArrayList<>();
        for (int d = 1; d <= 5; d++) {
            family.add(item("promo.example", d, GroundTruthLabel.SPAM));
        }
        List<SplitItem> items = new ArrayList<>(family);
        items.add(item("other.example", 50, GroundTruthLabel.HAM));
        items.add(item("third.example", 90, GroundTruthLabel.HAM));

        EvalSplit split = splitter.split(items, new SplitConfig(0.5, 1L));

        Set<SplitSide> familySides = new HashSet<>();
        for (SplitItem f : family) {
            familySides.add(split.sideOf(f.emailId()));
        }
        assertThat(familySides).hasSize(1);
        assertThat(split.audit().crossBoundaryGroups()).isZero();
    }

    @Test
    void holds_out_the_most_recent_families_for_eval() {
        SplitItem oldest = item("a.example", 1, GroundTruthLabel.HAM);
        SplitItem middle = item("b.example", 50, GroundTruthLabel.SPAM);
        SplitItem newest = item("c.example", 100, GroundTruthLabel.PHISH);

        EvalSplit split = splitter.split(List.of(oldest, middle, newest), new SplitConfig(0.34, 7L));

        assertThat(split.sideOf(newest.emailId())).isEqualTo(SplitSide.EVAL);
        assertThat(split.sideOf(oldest.emailId())).isEqualTo(SplitSide.TRAIN);
    }

    @Test
    void treats_an_email_with_no_group_as_its_own_singleton() {
        // Null and blank group ids must each become a distinct group, never merge
        // into one "no-family" bucket that would span the boundary.
        List<SplitItem> items = List.of(
                new SplitItem(UUID.randomUUID(), null, day(1), GroundTruthLabel.HAM),
                new SplitItem(UUID.randomUUID(), "   ", day(2), GroundTruthLabel.SPAM),
                new SplitItem(UUID.randomUUID(), null, day(3), GroundTruthLabel.PHISH));

        EvalSplit split = splitter.split(items, new SplitConfig(0.34, 1L));

        assertThat(split.audit().groupCount()).isEqualTo(3);
        assertThat(split.audit().crossBoundaryGroups()).isZero();
    }

    @Test
    void is_reproducible_for_the_same_corpus_and_seed() {
        List<SplitItem> items = manySingletonFamilies(60);
        SplitConfig config = new SplitConfig(0.3, 12345L);

        Map<UUID, SplitSide> first = splitter.split(items, config).sides();
        Map<UUID, SplitSide> second = splitter.split(items, config).sides();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void orders_time_distinct_families_identically_regardless_of_seed() {
        // The timeline does the assigning; the seed only breaks ties. With distinct
        // timestamps there are no ties, so the seed cannot change the outcome.
        List<SplitItem> items = manySingletonFamilies(40);

        Map<UUID, SplitSide> withSeedA = splitter.split(items, new SplitConfig(0.25, 1L)).sides();
        Map<UUID, SplitSide> withSeedB = splitter.split(items, new SplitConfig(0.25, 999L)).sides();

        assertThat(withSeedB).isEqualTo(withSeedA);
    }

    @Test
    void realizes_approximately_the_requested_eval_fraction() {
        List<SplitItem> items = manySingletonFamilies(100);

        EvalSplit split = splitter.split(items, new SplitConfig(0.25, 3L));

        assertThat(split.audit().evalCount()).isBetween(20, 30);
        assertThat(split.audit().trainCount() + split.audit().evalCount()).isEqualTo(100);
    }

    @Test
    void keeps_time_contiguous_families_inversion_free() {
        // Disjoint time ranges per family: the holdout is a clean time cut.
        List<SplitItem> items = List.of(
                item("a.example", 1, GroundTruthLabel.HAM),
                item("a.example", 2, GroundTruthLabel.HAM),
                item("b.example", 10, GroundTruthLabel.SPAM),
                item("b.example", 11, GroundTruthLabel.SPAM),
                item("c.example", 20, GroundTruthLabel.PHISH),
                item("c.example", 21, GroundTruthLabel.PHISH));

        EvalSplit split = splitter.split(items, new SplitConfig(0.34, 1L));

        assertThat(split.audit().temporalInversions()).isZero();
        assertThat(split.audit().leakageFree()).isTrue();
    }

    @Test
    void counts_temporal_inversions_honestly_when_an_indivisible_family_overlaps() {
        // "mix" recurs early (day 5) and late (day 90); its recency lands it in eval,
        // but it cannot be split, so its day-5 member ends up older than the newest
        // train email (day 20). Grouping wins; the overlap is reported, not hidden.
        SplitItem oldTrain = item("old.example", 20, GroundTruthLabel.HAM);
        SplitItem mixEarly = item("mix.example", 5, GroundTruthLabel.SPAM);
        SplitItem mixLate = item("mix.example", 90, GroundTruthLabel.SPAM);
        SplitItem newest = item("new.example", 100, GroundTruthLabel.PHISH);

        EvalSplit split =
                splitter.split(List.of(oldTrain, mixEarly, mixLate, newest), new SplitConfig(0.5, 1L));

        assertThat(split.sideOf(mixEarly.emailId())).isEqualTo(SplitSide.EVAL);
        assertThat(split.sideOf(mixLate.emailId())).isEqualTo(SplitSide.EVAL);
        assertThat(split.sideOf(oldTrain.emailId())).isEqualTo(SplitSide.TRAIN);
        assertThat(split.audit().crossBoundaryGroups()).isZero();
        assertThat(split.audit().temporalInversions()).isEqualTo(1);
        assertThat(split.audit().leakageFree()).isFalse();
    }

    @Test
    void returns_an_empty_split_for_an_empty_corpus() {
        EvalSplit split = splitter.split(List.of(), new SplitConfig(0.2, 1L));

        assertThat(split.sides()).isEmpty();
        assertThat(split.audit().total()).isZero();
        assertThat(split.audit().leakageFree()).isTrue();
    }

    @Test
    void keeps_a_lone_family_entirely_in_train() {
        // One family cannot be split without leaking, so it all trains and eval is empty.
        List<SplitItem> items = List.of(
                item("solo.example", 1, GroundTruthLabel.SPAM),
                item("solo.example", 2, GroundTruthLabel.SPAM),
                item("solo.example", 3, GroundTruthLabel.SPAM));

        EvalSplit split = splitter.split(items, new SplitConfig(0.5, 1L));

        assertThat(split.audit().trainCount()).isEqualTo(3);
        assertThat(split.audit().evalCount()).isZero();
        assertThat(split.audit().crossBoundaryGroups()).isZero();
    }

    @Test
    void rejects_an_eval_fraction_outside_the_open_unit_interval() {
        assertThatThrownBy(() -> new SplitConfig(0.0, 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SplitConfig(1.0, 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SplitConfig(-0.1, 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SplitConfig(1.5, 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SplitConfig(Double.NaN, 1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_random_split_leaks_families_that_the_grouped_split_keeps_whole() {
        // The honest-gap demonstration: an arena-heavy corpus where every family has
        // many near-duplicate members. A random split shatters families across the
        // boundary (leakage); the grouped split keeps every one whole.
        List<SplitItem> items = new ArrayList<>();
        for (int family = 0; family < 6; family++) {
            for (int member = 0; member < 6; member++) {
                items.add(item("fam-" + family + ".example", family * 10 + member, GroundTruthLabel.SPAM));
            }
        }

        int groupedCrossBoundary = splitter.split(items, new SplitConfig(0.5, 1L)).audit().crossBoundaryGroups();
        int randomCrossBoundary = crossBoundaryFamiliesOfRandomSplit(items, 0.5, 1L);

        assertThat(groupedCrossBoundary).isZero();
        assertThat(randomCrossBoundary).isGreaterThan(0);
    }

    /** A naive random 50/50 split, used only to show the leakage a grouped split avoids. */
    private static int crossBoundaryFamiliesOfRandomSplit(List<SplitItem> items, double evalFraction, long seed) {
        List<SplitItem> shuffled = new ArrayList<>(items);
        java.util.Collections.shuffle(shuffled, new Random(seed));
        int trainSize = (int) Math.round((1 - evalFraction) * shuffled.size());
        Map<String, Set<SplitSide>> sidesByFamily = new HashMap<>();
        for (int i = 0; i < shuffled.size(); i++) {
            SplitSide side = i < trainSize ? SplitSide.TRAIN : SplitSide.EVAL;
            sidesByFamily.computeIfAbsent(shuffled.get(i).groupId(), k -> new HashSet<>()).add(side);
        }
        return (int) sidesByFamily.values().stream().filter(s -> s.size() > 1).count();
    }

    private static List<SplitItem> manySingletonFamilies(int n) {
        List<SplitItem> items = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            items.add(item("family-" + i + ".example", i + 1, GroundTruthLabel.values()[i % 3]));
        }
        return items;
    }
}
