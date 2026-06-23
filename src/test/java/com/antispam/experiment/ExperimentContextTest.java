package com.antispam.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The read-only experiment scope's contract (story 09.03): {@link ExperimentContext#runReadOnly}
 * marks the current thread as a read-only experiment for the duration of the work and restores the
 * prior state on exit (even when the work throws); inside the scope {@code requireLiveWritePermitted}
 * rejects a live-state write, outside it the same call is a no-op. The flag is thread-bound, so two
 * threads never see each other's scope.
 */
class ExperimentContextTest {

    @AfterEach
    void noLeakedScope() {
        // A leaked scope would silently poison every later test on this thread, so prove it's clean.
        assertThat(ExperimentContext.isReadOnly()).isFalse();
    }

    @Test
    void a_thread_is_not_read_only_by_default() {
        assertThat(ExperimentContext.isReadOnly()).isFalse();
        assertThatCode(() -> ExperimentContext.requireLiveWritePermitted("reputation_events"))
                .doesNotThrowAnyException();
    }

    @Test
    void marks_the_thread_read_only_only_for_the_duration_of_the_work() {
        ExperimentContext.runReadOnly(() ->
                assertThat(ExperimentContext.isReadOnly()).isTrue());

        assertThat(ExperimentContext.isReadOnly()).isFalse();
    }

    @Test
    void restores_the_prior_state_even_when_the_work_throws() {
        assertThatThrownBy(() -> ExperimentContext.runReadOnly(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(ExperimentContext.isReadOnly()).isFalse();
    }

    @Test
    void blocks_a_live_state_write_inside_the_scope_naming_the_table() {
        ExperimentContext.runReadOnly(() ->
                assertThatThrownBy(() -> ExperimentContext.requireLiveWritePermitted("feedback_events"))
                        .isInstanceOf(LiveStateWriteForbiddenException.class)
                        .hasMessageContaining("feedback_events"));
    }

    @Test
    void callReadOnly_returns_the_works_value() {
        String result = ExperimentContext.callReadOnly(() -> {
            assertThat(ExperimentContext.isReadOnly()).isTrue();
            return "scored";
        });

        assertThat(result).isEqualTo("scored");
        assertThat(ExperimentContext.isReadOnly()).isFalse();
    }

    @Test
    void nested_scopes_stay_read_only_until_the_outermost_exits() {
        ExperimentContext.runReadOnly(() -> {
            ExperimentContext.runReadOnly(() ->
                    assertThat(ExperimentContext.isReadOnly()).isTrue());
            // The inner scope ending must not clear the outer one.
            assertThat(ExperimentContext.isReadOnly()).isTrue();
        });

        assertThat(ExperimentContext.isReadOnly()).isFalse();
    }

    @Test
    void the_scope_does_not_leak_to_another_thread() throws InterruptedException {
        boolean[] otherThreadSawReadOnly = {true};

        ExperimentContext.runReadOnly(() -> {
            Thread other = new Thread(() -> otherThreadSawReadOnly[0] = ExperimentContext.isReadOnly());
            other.start();
            try {
                other.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(otherThreadSawReadOnly[0]).isFalse();
    }
}
