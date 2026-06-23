package com.antispam.campaign;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A coordinated-campaign group as the clusterer produces it: a centroid plus its
 * members. Holds no database identity of its own — {@link #deterministicId()}
 * derives a stable id from the member set so re-running the offline job over the
 * same emails yields the same cluster ids, which is what makes the grouped eval
 * split (Epic 11) reproducible.
 *
 * @param centroid the L2-normalized mean of the members' embeddings
 * @param members  the emails in the cluster, each with its similarity to {@code centroid}
 */
public record EmailCluster(float[] centroid, List<ClusterMember> members) {

    public EmailCluster {
        if (centroid == null || centroid.length == 0) {
            throw new IllegalArgumentException("centroid is required and non-empty");
        }
        if (members == null || members.isEmpty()) {
            throw new IllegalArgumentException("a cluster has at least one member");
        }
        members = List.copyOf(members);
    }

    /** Number of emails in the cluster. */
    public int size() {
        return members.size();
    }

    /**
     * A content-addressed id: UUIDv3 over the cluster's member ids in sorted order.
     * Identity comes from <em>who</em> is in the cluster, not from when it was
     * created or in what order the job visited the emails — so the same partition
     * always maps to the same id, across runs and across machines (AC 5).
     */
    public UUID deterministicId() {
        String key = members.stream()
                .map(m -> m.emailId().toString())
                .sorted()
                .collect(Collectors.joining(","));
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
