package com.antispam.eval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Collapses every grouping relation the corpus carries into one effective family key
 * per email — the enrichment story 11.01 asks for. The bootstrap keyed a family by
 * sender domain alone; this resolver unions that proxy with the two real lineage
 * signals the spine now produces:
 *
 * <ul>
 *   <li><b>Campaign clusters</b> (Epic 06.03): every member of a cluster — the
 *       reworded variants offline clustering caught — is one family.</li>
 *   <li><b>Arena mutation lineage</b> (Epic 08.01): a minted variant and the real
 *       seed it descends from are one family, so a perturbed near-twin can never
 *       train while its sibling is judged.</li>
 *   <li><b>Sender domain</b>: the bootstrap proxy, kept as the fallback that still
 *       groups templated blasts no cluster or lineage covers.</li>
 * </ul>
 *
 * <p>The relations are unioned, not layered: if a campaign cluster and a lineage
 * chain share a single email, the whole transitive closure is one indivisible family.
 * That transitivity is the point — leakage hides in the seam between two relations
 * (a cluster member that is also someone's mutation parent), and a per-relation key
 * would miss it. Connectivity is computed with union-find over every node the
 * relations mention, including ones outside the labeled corpus (a real spam seed need
 * not itself be labeled, yet it still chains two of its variants together); keys are
 * emitted only for corpus emails.
 *
 * <p>An email that no relation mentions has no family and is keyed {@code null}, which
 * the splitter turns into a per-email singleton. The family key itself is an opaque,
 * reproducible identifier (the component's lexicographically smallest member id) — its
 * only contract is that two emails share it exactly when they share a family, which is
 * what the split audit checks.
 */
@Component
public class CorpusGroupResolver {

    /**
     * Resolves each corpus email to its effective family key.
     *
     * @param universe the labeled corpus — the only emails a key is emitted for
     * @param relations groups of email ids known to belong together; each group is one
     *                  relation instance (a cluster's members, a domain's emails, or a
     *                  {@code {variant, seed}} lineage pair). Ids outside {@code universe}
     *                  may appear as connectors and are unioned but never keyed.
     * @return every {@code universe} email mapped to its family key, or to {@code null}
     *         when no relation mentions it (a singleton)
     */
    public Map<UUID, String> resolve(Set<UUID> universe, List<List<UUID>> relations) {
        UnionFind families = new UnionFind();
        Set<UUID> grouped = new HashSet<>();
        for (List<UUID> relation : relations) {
            UUID anchor = null;
            for (UUID member : relation) {
                families.add(member);
                if (universe.contains(member)) {
                    grouped.add(member);
                }
                if (anchor == null) {
                    anchor = member;
                } else {
                    families.union(anchor, member);
                }
            }
        }

        Map<UUID, UUID> representativeByRoot = smallestMemberByRoot(families);
        Map<UUID, String> keys = new HashMap<>(universe.size() * 2);
        for (UUID email : universe) {
            if (grouped.contains(email)) {
                keys.put(email, "fam:" + representativeByRoot.get(families.find(email)));
            } else {
                keys.put(email, null);
            }
        }
        return keys;
    }

    /**
     * The lexicographically smallest member of each component, keyed by the component's
     * root. Taking the min over <em>all</em> members (corpus and connector alike) makes
     * the family key a deterministic function of the component, independent of which
     * node union-find happened to elect as root.
     */
    private static Map<UUID, UUID> smallestMemberByRoot(UnionFind families) {
        Map<UUID, UUID> smallest = new HashMap<>();
        for (UUID node : families.nodes()) {
            UUID root = families.find(node);
            smallest.merge(root, node, (a, b) -> a.compareTo(b) <= 0 ? a : b);
        }
        return smallest;
    }

    /** A minimal union-find: path-compressed find, union by accumulated members. */
    private static final class UnionFind {

        private final Map<UUID, UUID> parent = new HashMap<>();

        private void add(UUID node) {
            parent.putIfAbsent(node, node);
        }

        private UUID find(UUID node) {
            UUID root = node;
            while (!root.equals(parent.get(root))) {
                root = parent.get(root);
            }
            // Path compression: re-point the walked nodes straight at the root.
            UUID cursor = node;
            while (!cursor.equals(root)) {
                UUID next = parent.get(cursor);
                parent.put(cursor, root);
                cursor = next;
            }
            return root;
        }

        private void union(UUID a, UUID b) {
            UUID rootA = find(a);
            UUID rootB = find(b);
            if (!rootA.equals(rootB)) {
                // Deterministic merge direction (smaller id wins as root) so the
                // structure does not depend on insertion order.
                if (rootA.compareTo(rootB) <= 0) {
                    parent.put(rootB, rootA);
                } else {
                    parent.put(rootA, rootB);
                }
            }
        }

        private List<UUID> nodes() {
            return new ArrayList<>(parent.keySet());
        }
    }
}
