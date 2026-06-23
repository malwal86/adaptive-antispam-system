package com.antispam.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The thread pool that runs live shadow scoring off the request thread (story 09.02). The shadow
 * policy is scored and recorded asynchronously so it adds zero latency to the enforced decision and
 * a shadow-side failure can never affect the live verdict — the "zero user impact" guarantee.
 *
 * <p>A modest pool with a bounded queue: shadow scoring reuses the live decision's already-computed
 * model output (no model re-run), so each task is cheap, but bounding the queue means a backlog
 * sheds shadow work rather than growing unbounded under load — shadow evidence is best-effort, the
 * live path is not. Tests override this with a synchronous executor so the recorded row is
 * observable deterministically.
 */
@Configuration
public class ShadowExecutorConfig {

    @Bean
    public Executor shadowScoringExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("shadow-score-");
        // Shadow work is best-effort: if the pool and queue are saturated, drop the task rather than
        // block the caller (the live decision thread) or grow memory. A dropped shadow score costs
        // only a missing evidence row, never a wrong or delayed live decision.
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
