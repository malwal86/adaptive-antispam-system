package com.antispam.decision.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.decision.Classification;
import com.antispam.decision.ClassificationRepository;
import com.antispam.decision.Decision;
import com.antispam.decision.ModelScores;
import com.antispam.decision.RouteUsed;
import com.antispam.decision.routing.RoutingReason;
import com.antispam.ingest.Email;
import com.antispam.ingest.IngestService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The quarantine-pending lifecycle against real Postgres (story 05.06). With the LLM enabled, a
 * pending decision is recorded synchronously and the resolution is appended as a second row — the
 * two-row promote/confirm/degrade history. The LLM provider is mocked at the {@link LlmChatPort}
 * seam (no network), and the resolution executor is made synchronous so the appended row is present
 * deterministically by the time the call returns.
 *
 * <p>The routing that selects uncertain mail is exercised in {@link
 * com.antispam.decision.FusionIntegrationTest}; here the resolver is driven directly so the
 * persistence and never-deliver-then-retract invariant are pinned without re-deriving a routed
 * posterior.
 */
@TestPropertySource(properties = "antispam.llm.enabled=true")
class QuarantinePendingIntegrationTest extends AbstractPostgresIntegrationTest {

    @TestConfiguration
    static class SynchronousResolutionConfig {
        // Resolve inline so the appended resolution row is durable before the test assertions run.
        @Bean
        Executor llmResolutionExecutor() {
            return Runnable::run;
        }
    }

    @MockitoBean
    private LlmChatPort chatPort;

    @Autowired
    private QuarantinePendingService service;

    @Autowired
    private ClassificationRepository classifications;

    @Autowired
    private IngestService ingestService;

    private Email ingest(String raw) {
        var result = ingestService.ingest(raw.getBytes(StandardCharsets.UTF_8), "test");
        return ingestService.findById(result.emailId()).orElseThrow();
    }

    private QuarantinePendingService.ResolutionRequest requestFor(Email email) {
        return new QuarantinePendingService.ResolutionRequest(
                email, List.of(RoutingReason.NEW_SENDER_UNCERTAINTY),
                new ModelScores(0.5, 0.3, "bootstrap-v1", 0.5), null, "bootstrap-v1",
                Decision.ALLOW, Decision.ALLOW, 5L);
    }

    private static String verdictJson(String verdict) {
        return """
                {"verdict":"%s","spam_prob":0.9,"phishing_prob":0.05,
                 "reason_codes":["BENIGN_CONTENT"],"explanation_short":"x"}
                """.formatted(verdict);
    }

    @Test
    void a_spam_verdict_records_pending_then_a_confirmed_resolution() {
        Email email = ingest("""
                From: sender@uncertain.example
                Subject: ambiguous [qp-it-spam]

                Could you confirm the details we discussed?
                """);
        when(chatPort.complete(anyString(), anyString()))
                .thenReturn(new LlmRawResponse(verdictJson("SPAM"), 800, 60));

        Classification pending = service.beginResolution(requestFor(email));

        // Synchronous response is the withheld quarantine-pending row.
        assertThat(pending.decision()).isEqualTo(Decision.QUARANTINE);
        assertThat(pending.route()).isEqualTo(RouteUsed.LLM);

        // The resolution was appended: two rows, pending first then the confirmed resolution.
        List<Classification> rows = classifications.findByEmailId(email.id());
        assertThat(rows).hasSize(2);
        Classification resolved = rows.get(1);
        assertThat(resolved.decision()).isEqualTo(Decision.QUARANTINE); // SPAM confirmed -> withheld
        assertThat(resolved.route()).isEqualTo(RouteUsed.LLM);
        assertThat(resolved.llmCostUsd()).isNotNull(); // the real call was billed
        // Never deliver-then-retract: every row in the history withholds the mail.
        assertThat(rows).allSatisfy(row -> assertThat(row.decision().delivers()).isFalse());
    }

    @Test
    void a_legitimate_verdict_promotes_the_pending_mail_to_inbox() {
        Email email = ingest("""
                From: colleague@uncertain.example
                Subject: ambiguous [qp-it-ham]

                Thanks for the update, talk soon.
                """);
        when(chatPort.complete(anyString(), anyString()))
                .thenReturn(new LlmRawResponse(verdictJson("LEGITIMATE"), 800, 60));

        service.beginResolution(requestFor(email));

        List<Classification> rows = classifications.findByEmailId(email.id());
        assertThat(rows).hasSize(2);
        // Pending was withheld; the resolution promotes it to a delivered ALLOW — a single, forward
        // transition out of withholding, never a retraction.
        assertThat(rows.get(0).decision()).isEqualTo(Decision.QUARANTINE);
        assertThat(rows.get(0).decision().delivers()).isFalse();
        assertThat(rows.get(1).decision()).isEqualTo(Decision.ALLOW);
        assertThat(rows.get(1).decision().delivers()).isTrue();
    }
}
