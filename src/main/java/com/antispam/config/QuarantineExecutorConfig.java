package com.antispam.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The thread pools that drive the async quarantine-pending resolution (story 05.06). Two distinct
 * pools by design:
 *
 * <ul>
 *   <li>{@code llmResolutionExecutor} runs the off-request resolution itself, so the synchronous
 *       decision path returns the quarantine-pending verdict within its &lt;100ms budget without
 *       waiting on the LLM;</li>
 *   <li>{@code llmSlaExecutor} runs the actual LLM call inside the resolution, so the resolver can
 *       bound it with a {@code Future.get(SLA)} deadline and fail-degrade if the call overruns —
 *       a separate pool so a slow call cannot starve the resolution pool of threads.</li>
 * </ul>
 *
 * <p>Both are modest fixed pools: the LLM is the budgeted, low-volume ~5% path, not the hot path, so
 * a large pool would only invite more concurrent spend. Tests override these with synchronous
 * executors so the lifecycle is deterministic.
 */
@Configuration
public class QuarantineExecutorConfig {

    @Bean
    public Executor llmResolutionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("llm-resolve-");
        executor.initialize();
        return executor;
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService llmSlaExecutor() {
        return Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "llm-sla-call");
            thread.setDaemon(true);
            return thread;
        });
    }
}
