package com.antispam.decision.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The composite's ordering contract (story 10.04): classpath first, remote only on a classpath miss,
 * and a uniform not-found when neither store has the version. The classpath-first rule is what keeps a
 * Supabase outage from touching the bundled bootstrap model, so the test pins that the remote store is
 * never consulted on a classpath hit.
 */
@ExtendWith(MockitoExtension.class)
class CompositeModelArtifactStoreTest {

    private static final byte[] CLASSPATH_BYTES = {1, 2, 3};
    private static final byte[] REMOTE_BYTES = {4, 5, 6};

    @Mock
    private ClasspathModelArtifactStore classpath;

    @Mock
    private SupabaseModelArtifactStore remote;

    @Mock
    private ObjectProvider<SupabaseModelArtifactStore> remoteProvider;

    private CompositeModelArtifactStore composite() {
        return new CompositeModelArtifactStore(classpath, remoteProvider);
    }

    @Test
    void serves_from_the_classpath_without_consulting_remote() {
        when(classpath.modelBytes("v1")).thenReturn(CLASSPATH_BYTES);

        byte[] bytes = composite().modelBytes("v1");

        assertThat(bytes).isEqualTo(CLASSPATH_BYTES);
        verifyNoInteractions(remoteProvider, remote);
    }

    @Test
    void falls_through_to_remote_on_a_classpath_miss() {
        when(classpath.modelBytes("candidate-v2"))
                .thenThrow(new ModelArtifactNotFoundException("not in jar"));
        when(remoteProvider.getIfAvailable()).thenReturn(remote);
        when(remote.modelBytes("candidate-v2")).thenReturn(REMOTE_BYTES);

        byte[] bytes = composite().modelBytes("candidate-v2");

        assertThat(bytes).isEqualTo(REMOTE_BYTES);
        verify(remote).modelBytes("candidate-v2");
    }

    @Test
    void falls_through_to_remote_for_metadata_on_a_classpath_miss() {
        when(classpath.metadataBytes("candidate-v2"))
                .thenThrow(new ModelArtifactNotFoundException("not in jar"));
        when(remoteProvider.getIfAvailable()).thenReturn(remote);
        when(remote.metadataBytes("candidate-v2")).thenReturn(REMOTE_BYTES);

        byte[] bytes = composite().metadataBytes("candidate-v2");

        assertThat(bytes).isEqualTo(REMOTE_BYTES);
        verify(remote).metadataBytes("candidate-v2");
    }

    @Test
    void reports_not_found_when_classpath_misses_and_no_remote_is_configured() {
        when(classpath.modelBytes("candidate-v2"))
                .thenThrow(new ModelArtifactNotFoundException("not in jar"));
        when(remoteProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> composite().modelBytes("candidate-v2"))
                .isInstanceOf(ModelArtifactNotFoundException.class)
                .hasMessageContaining("candidate-v2")
                .hasMessageContaining("no remote store");
    }

    @Test
    void surfaces_a_remote_not_found_when_neither_store_has_it() {
        lenient().when(classpath.modelBytes("ghost-v3"))
                .thenThrow(new ModelArtifactNotFoundException("not in jar"));
        when(remoteProvider.getIfAvailable()).thenReturn(remote);
        when(remote.modelBytes("ghost-v3"))
                .thenThrow(new ModelArtifactNotFoundException("not in Supabase"));

        assertThatThrownBy(() -> composite().modelBytes("ghost-v3"))
                .isInstanceOf(ModelArtifactNotFoundException.class);
        verify(classpath, never()).metadataBytes("ghost-v3");
    }
}
