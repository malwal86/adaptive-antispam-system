package com.antispam.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.antispam.seed.GroundTruthLabel;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The eval-set service's two guarantees (story 11.02), pinned with mocked repositories so they need no
 * database: a golden version is frozen with the split configuration it came from, and a frozen version
 * is never redefined — the service refuses to re-freeze an existing label before any write is attempted,
 * the application-level half of the immutability the database also enforces.
 */
@ExtendWith(MockitoExtension.class)
class EvalSetServiceTest {

    private static final EvalSplitProperties SPLIT = new EvalSplitProperties(0.2, 42L);

    @Mock
    private GoldenSetRepository goldenSets;

    @Mock
    private FreshChallengeRepository freshSet;

    private EvalSetService service() {
        return new EvalSetService(goldenSets, freshSet, SPLIT);
    }

    @Test
    void freezes_a_new_version_with_the_split_configuration_it_came_from() {
        GoldenSetVersion frozen = new GoldenSetVersion("golden-1", 0.2, 42L, 7, Instant.EPOCH);
        when(goldenSets.versionExists("golden-1")).thenReturn(false);
        when(goldenSets.freeze("golden-1", 0.2, 42L)).thenReturn(frozen);

        assertThat(service().freezeGolden("golden-1")).isEqualTo(frozen);
        verify(goldenSets).freeze("golden-1", 0.2, 42L);
    }

    @Test
    void refuses_to_redefine_an_already_frozen_version() {
        when(goldenSets.versionExists("golden-1")).thenReturn(true);

        assertThatThrownBy(() -> service().freezeGolden("golden-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already frozen");
        verify(goldenSets, never()).freeze(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void rejects_a_blank_version_label() {
        assertThatThrownBy(() -> service().freezeGolden("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
        verify(goldenSets, never()).versionExists(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void appends_a_reported_attack_only_to_the_fresh_set() {
        UUID emailId = UUID.randomUUID();

        service().addFreshChallenge(emailId, GroundTruthLabel.PHISH, "reported");

        verify(freshSet).add(emailId, GroundTruthLabel.PHISH, "reported");
    }
}
