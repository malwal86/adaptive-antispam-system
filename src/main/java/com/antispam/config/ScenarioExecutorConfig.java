package com.antispam.config;

import com.antispam.scenario.ScenarioDispatcher;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The single background thread that runs the thunderclap scenario's injection loop (story 12.05).
 * The runner paces emails out over seconds so the demo's beats unfold visibly, so it must not run on
 * the request thread; one thread is enough because the scenario guards against overlapping runs.
 *
 * <p>The {@link ScenarioDispatcher} wraps the managed executor behind a one-method seam, so tests can
 * substitute a synchronous dispatcher (run inline) without touching this pool.
 */
@Configuration
public class ScenarioExecutorConfig {

    @Bean
    public Executor scenarioExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        // A small queue: at most one run is active (the runner's guard), so this only absorbs a
        // start that races the previous run's teardown.
        executor.setQueueCapacity(2);
        executor.setThreadNamePrefix("scenario-");
        executor.initialize();
        return executor;
    }

    @Bean
    public ScenarioDispatcher scenarioDispatcher(@Qualifier("scenarioExecutor") Executor scenarioExecutor) {
        return scenarioExecutor::execute;
    }
}
