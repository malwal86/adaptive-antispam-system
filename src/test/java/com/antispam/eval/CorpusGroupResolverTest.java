package com.antispam.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The resolver is the enrichment half of story 11.01: it turns the raw grouping
 * relations (sender domain, campaign-cluster membership, arena mutation lineage)
 * into one effective family key per email, merging anything that shares any relation
 * so a reworded or mutated near-duplicate can never sit on the opposite side of the
 * split from its twin. It is tested as a pure unit — the relations are hand-built —
 * because the union of relations is exactly where leakage would creep back in.
 */
class CorpusGroupResolverTest {

    private final CorpusGroupResolver resolver = new CorpusGroupResolver();

    private static UUID id() {
        return UUID.randomUUID();
    }

    @Test
    void emails_sharing_a_single_relation_get_the_same_family_key() {
        UUID a = id();
        UUID b = id();
        Map<UUID, String> keys = resolver.resolve(Set.of(a, b), List.of(List.of(a, b)));

        assertThat(keys.get(a)).isNotNull().isEqualTo(keys.get(b));
    }

    @Test
    void lineage_chains_two_variants_through_their_shared_seed_even_when_the_seed_is_uncorpused() {
        // The seed is real spam that may not be a labeled corpus member, yet it is the
        // connective tissue: two variants minted from it must land in one family. The
        // resolver unions through the seed but only emits keys for corpus emails.
        UUID seed = id();
        UUID variantOne = id();
        UUID variantTwo = id();
        Set<UUID> corpus = Set.of(variantOne, variantTwo);

        Map<UUID, String> keys = resolver.resolve(
                corpus, List.of(List.of(variantOne, seed), List.of(variantTwo, seed)));

        assertThat(keys.get(variantOne)).isNotNull().isEqualTo(keys.get(variantTwo));
        assertThat(keys).doesNotContainKey(seed);
    }

    @Test
    void distinct_relations_transitively_merge_into_one_family() {
        // A campaign cluster {a, b} and a lineage pair {b, c}: because b is in both,
        // all three are one indivisible family — exactly the cross-relation leakage
        // grouping by domain alone would miss.
        UUID a = id();
        UUID b = id();
        UUID c = id();

        Map<UUID, String> keys = resolver.resolve(
                Set.of(a, b, c), List.of(List.of(a, b), List.of(b, c)));

        assertThat(keys.get(a)).isNotNull();
        assertThat(keys.get(b)).isEqualTo(keys.get(a));
        assertThat(keys.get(c)).isEqualTo(keys.get(a));
    }

    @Test
    void an_email_with_no_relation_is_its_own_singleton_keyed_null() {
        UUID grouped = id();
        UUID other = id();
        UUID lonely = id();

        Map<UUID, String> keys =
                resolver.resolve(Set.of(grouped, other, lonely), List.of(List.of(grouped, other)));

        // The splitter's singleton rule keys a null group per-email; the resolver
        // signals "no family" with null rather than inventing a shared bucket.
        assertThat(keys.get(lonely)).isNull();
        assertThat(keys.get(grouped)).isNotNull();
    }

    @Test
    void a_singleton_relation_still_yields_a_stable_non_null_key() {
        // A unique sender domain is a family of one — it has a grouping signal, so it
        // is keyed (distinct from a relationless email), and the splitter treats it as
        // a one-member family either way.
        UUID solo = id();

        Map<UUID, String> keys = resolver.resolve(Set.of(solo), List.of(List.of(solo)));

        assertThat(keys.get(solo)).isNotNull();
    }

    @Test
    void the_same_relations_resolve_to_the_same_keys_every_time() {
        UUID a = id();
        UUID b = id();
        UUID c = id();
        List<List<UUID>> relations = List.of(List.of(a, b), List.of(c));

        Map<UUID, String> first = resolver.resolve(Set.of(a, b, c), relations);
        Map<UUID, String> second = resolver.resolve(Set.of(a, b, c), relations);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void members_outside_the_corpus_universe_never_receive_a_key() {
        UUID inside = id();
        UUID outside = id();

        Map<UUID, String> keys = resolver.resolve(Set.of(inside), List.of(List.of(inside, outside)));

        assertThat(keys).containsOnlyKeys(inside);
    }
}
