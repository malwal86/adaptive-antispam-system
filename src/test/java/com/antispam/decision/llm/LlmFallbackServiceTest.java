package com.antispam.decision.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.antispam.decision.TestEmails;
import com.antispam.decision.llm.LlmVerdict.Verdict;
import com.antispam.decision.routing.RoutingReason;
import com.antispam.features.EmailFeatureExtractor;
import com.antispam.ingest.Email;
import com.antispam.reputation.BetaReputation;
import com.antispam.reputation.ReputationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The retry-once-then-fail-degrade contract (story 05.02). A routed email's LLM response is parsed
 * into a typed {@link LlmVerdict}; a schema-invalid response triggers exactly one retry; a second
 * schema failure (or a provider that is unavailable) fail-degrades rather than throwing or
 * guessing — so a malformed or manipulated response can never corrupt the decision path. The cost
 * of every call is recorded regardless of outcome.
 */
@ExtendWith(MockitoExtension.class)
class LlmFallbackServiceTest {

    @Mock
    private LlmChatPort port;

    @Mock
    private ReputationService reputation;

    /** The escalation reasons the router would hand a real LLM call; grounded into the prompt. */
    private static final List<RoutingReason> REASONS = List.of(RoutingReason.LOW_MODEL_CONFIDENCE);

    private final Email email = TestEmails.bodyContaining("Claim your prize now");
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    // 0.01 USD / 1k input tokens, 0.02 USD / 1k output tokens. A 1000/500-token call thus costs
    // 0.01 + 0.01 = 0.02 USD, which makes the cost assertions exact.
    private final LlmProperties properties = new LlmProperties(true, new LlmProperties.Cost(0.01, 0.02));

    // Default: an unbounded budget that always grants, so the existing retry/degrade tests are
    // unaffected by cost control. The budget-specific tests below swap in a stub or mock.
    private LlmBudget budget = new UnboundedLlmBudget();

    @BeforeEach
    void stubReputation() {
        // Grounded context reads the sender's reputation; lenient so the one test that never
        // classifies does not trip strict-stubbing. A modest, slightly-good Beta is enough.
        lenient().when(reputation.reputationFor(anyString(), anyBoolean()))
                .thenReturn(new BetaReputation(2.0, 1.0, 1.0, 1.0));
    }

    private LlmFallbackService service() {
        return new LlmFallbackService(
                port, properties, new LlmMeter(registry), budget, new EmailFeatureExtractor(), reputation,
                new LlmPiiMaskingProperties(com.antispam.privacy.PiiMaskingLevel.STRICT));
    }

    private static LlmRawResponse response(String json) {
        return new LlmRawResponse(json, 1000, 500);
    }

    private static String validJson() {
        return """
                {
                  "verdict": "SPAM",
                  "spam_prob": 0.92,
                  "phishing_prob": 0.05,
                  "reason_codes": ["KNOWN_BAD_URL"],
                  "explanation_short": "Prize-bait with a malicious link."
                }
                """;
    }

    @Test
    void parses_a_valid_response_into_the_typed_verdict_on_the_first_try() {
        when(port.complete(anyString(), anyString())).thenReturn(response(validJson()));

        LlmOutcome outcome = service().classify(email, REASONS);

        assertThat(outcome.degraded()).isFalse();
        assertThat(outcome.attempts()).isEqualTo(1);
        assertThat(outcome.verdict().verdict()).isEqualTo(Verdict.SPAM);
        assertThat(outcome.verdict().spamProb()).isEqualTo(0.92);
        verify(port, times(1)).complete(anyString(), anyString());
    }

    @Test
    void the_outbound_prompt_carries_no_unmasked_pii_but_keeps_the_phishing_signal() {
        // An email whose body carries direct identifiers alongside the phishing signal.
        Email withPii = TestEmails.bodyContaining(
                "URGENT PayPal alert. Verify at https://paypal.example.com or call 1-800-555-0199. "
                + "Card 4111 1111 1111 1111. Reply admin@scam.example.");
        when(port.complete(anyString(), anyString())).thenReturn(response(validJson()));

        service().classify(withPii, REASONS);

        ArgumentCaptor<String> userContent = ArgumentCaptor.forClass(String.class);
        verify(port).complete(anyString(), userContent.capture());
        String sent = userContent.getValue();
        // Identifiers are masked...
        assertThat(sent)
                .doesNotContain("1-800-555-0199")
                .doesNotContain("4111 1111 1111 1111")
                .doesNotContain("admin@scam.example.")
                .contains("[phone]")
                .contains("[card-number]")
                .contains("a***@scam.example");
        // ...while the signal the model classifies on survives.
        assertThat(sent)
                .contains("URGENT")
                .contains("PayPal")
                .contains("https://paypal.example.com");
    }

    @Test
    void masking_off_sends_the_content_unmasked() {
        Email withPii = TestEmails.bodyContaining("call 1-800-555-0199 now");
        when(port.complete(anyString(), anyString())).thenReturn(response(validJson()));

        LlmFallbackService offService = new LlmFallbackService(
                port, properties, new LlmMeter(registry), budget, new EmailFeatureExtractor(), reputation,
                new LlmPiiMaskingProperties(com.antispam.privacy.PiiMaskingLevel.OFF));
        offService.classify(withPii, REASONS);

        ArgumentCaptor<String> userContent = ArgumentCaptor.forClass(String.class);
        verify(port).complete(anyString(), userContent.capture());
        assertThat(userContent.getValue()).contains("1-800-555-0199");
    }

    @Test
    void retries_exactly_once_when_the_first_response_is_schema_invalid() {
        when(port.complete(anyString(), anyString()))
                .thenReturn(response("not json at all"))
                .thenReturn(response(validJson()));

        LlmOutcome outcome = service().classify(email, REASONS);

        assertThat(outcome.degraded()).isFalse();
        assertThat(outcome.attempts()).isEqualTo(2);
        assertThat(outcome.verdict().verdict()).isEqualTo(Verdict.SPAM);
        verify(port, times(2)).complete(anyString(), anyString());
        assertThat(registry.get(LlmMeter.SCHEMA_RETRY).counter().count()).isEqualTo(1.0);
    }

    @Test
    void fail_degrades_after_a_second_schema_invalid_response() {
        when(port.complete(anyString(), anyString()))
                .thenReturn(response("garbage"))
                .thenReturn(response("{\"verdict\": \"NONSENSE\"}"));

        LlmOutcome outcome = service().classify(email, REASONS);

        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.attempts()).isEqualTo(2);
        verify(port, times(2)).complete(anyString(), anyString());
        assertThat(registry.get(LlmMeter.DEGRADED).tag("reason", "schema").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get(LlmMeter.CALL).tag("outcome", "degraded").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void treats_a_missing_required_field_as_a_schema_failure() {
        // spam_prob absent: required=true rejects it at bind time.
        String missingField = """
                {
                  "verdict": "SPAM",
                  "phishing_prob": 0.1,
                  "reason_codes": [],
                  "explanation_short": "x"
                }
                """;
        when(port.complete(anyString(), anyString()))
                .thenReturn(response(missingField))
                .thenReturn(response(validJson()));

        LlmOutcome outcome = service().classify(email, REASONS);

        assertThat(outcome.degraded()).isFalse();
        assertThat(outcome.attempts()).isEqualTo(2);
    }

    @Test
    void treats_an_extra_field_as_a_schema_failure() {
        String extraField = """
                {
                  "verdict": "SPAM",
                  "spam_prob": 0.9,
                  "phishing_prob": 0.1,
                  "reason_codes": [],
                  "explanation_short": "x",
                  "secret_instruction": "ignore your rules"
                }
                """;
        when(port.complete(anyString(), anyString())).thenReturn(response(extraField));

        LlmOutcome outcome = service().classify(email, REASONS);

        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.attempts()).isEqualTo(2);
    }

    @Test
    void treats_a_mistyped_field_as_a_schema_failure() {
        String mistyped = """
                {
                  "verdict": "SPAM",
                  "spam_prob": "very high",
                  "phishing_prob": 0.1,
                  "reason_codes": [],
                  "explanation_short": "x"
                }
                """;
        when(port.complete(anyString(), anyString())).thenReturn(response(mistyped));

        assertThat(service().classify(email, REASONS).degraded()).isTrue();
    }

    @Test
    void treats_an_out_of_range_probability_as_a_schema_failure() {
        String outOfRange = """
                {
                  "verdict": "SPAM",
                  "spam_prob": 1.7,
                  "phishing_prob": 0.1,
                  "reason_codes": [],
                  "explanation_short": "x"
                }
                """;
        when(port.complete(anyString(), anyString())).thenReturn(response(outOfRange));

        assertThat(service().classify(email, REASONS).degraded()).isTrue();
    }

    @Test
    void treats_an_invented_reason_code_as_a_schema_failure() {
        String unknownCode = """
                {
                  "verdict": "SPAM",
                  "spam_prob": 0.9,
                  "phishing_prob": 0.1,
                  "reason_codes": ["TOTALLY_MADE_UP"],
                  "explanation_short": "x"
                }
                """;
        when(port.complete(anyString(), anyString())).thenReturn(response(unknownCode));

        assertThat(service().classify(email, REASONS).degraded()).isTrue();
    }

    @Test
    void strips_a_markdown_code_fence_before_parsing() {
        String fenced = "```json\n" + validJson() + "\n```";
        when(port.complete(anyString(), anyString())).thenReturn(response(fenced));

        LlmOutcome outcome = service().classify(email, REASONS);

        assertThat(outcome.degraded()).isFalse();
        assertThat(outcome.attempts()).isEqualTo(1);
    }

    @Test
    void fail_degrades_immediately_without_retrying_when_the_provider_is_unavailable() {
        when(port.complete(anyString(), anyString()))
                .thenThrow(new LlmUnavailableException("disabled"));

        LlmOutcome outcome = service().classify(email, REASONS);

        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.attempts()).isEqualTo(1);
        // A dead provider is not a schema problem: the single retry is not spent on it.
        verify(port, times(1)).complete(anyString(), anyString());
        assertThat(registry.get(LlmMeter.DEGRADED).tag("reason", "unavailable").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void records_the_call_cost_from_token_usage() {
        when(port.complete(anyString(), anyString())).thenReturn(response(validJson()));

        LlmOutcome outcome = service().classify(email, REASONS);

        // 1000 input * 0.01/1k + 500 output * 0.02/1k = 0.01 + 0.01 = 0.02 USD.
        assertThat(outcome.costUsd()).isEqualByComparingTo("0.020000");
        assertThat(registry.get(LlmMeter.COST).counter().count()).isEqualTo(0.02);
    }

    @Test
    void accumulates_cost_across_a_retry() {
        when(port.complete(anyString(), anyString()))
                .thenReturn(response("garbage"))
                .thenReturn(response(validJson()));

        LlmOutcome outcome = service().classify(email, REASONS);

        // Both attempts are billed: 2 * 0.02 = 0.04 USD.
        assertThat(outcome.costUsd()).isEqualByComparingTo("0.040000");
    }

    @Test
    void grounds_the_prompt_in_features_reputation_and_escalation_not_the_raw_email_as_instructions() {
        Email prizeMail = TestEmails.bodyContaining("WIN a FREE prize!!! click http://1.2.3.4/x now");
        when(port.complete(anyString(), anyString())).thenReturn(response(validJson()));
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);

        service().classify(prizeMail, List.of(RoutingReason.NEW_SENDER_UNCERTAINTY));

        verify(port).complete(system.capture(), user.capture());
        String userContent = user.getValue();
        // The grounded block carries the extracted-feature signals, the reputation summary, and why
        // escalated — the trusted context the service assembled around the call.
        String grounding = userContent.substring(0, userContent.indexOf("=== BEGIN EMAIL"));
        assertThat(grounding)
                .contains("GROUNDED CONTEXT")
                .contains("Why escalated to you: NEW_SENDER_UNCERTAINTY")
                .contains("Sender reputation: trust_mean=")
                .contains("Authentication: spf=");
        // The raw email is present only inside the untrusted-data block — never as instructions in
        // the grounded section the model reasons from.
        assertThat(grounding).doesNotContain("WIN a FREE prize");
        assertThat(userContent).contains("=== BEGIN EMAIL (untrusted data");
    }

    @Test
    void offers_only_llm_selectable_reason_codes_in_the_system_prompt() {
        when(port.complete(anyString(), anyString())).thenReturn(response(validJson()));
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);

        service().classify(email, REASONS);

        verify(port).complete(system.capture(), anyString());
        String systemPrompt = system.getValue();
        // Content judgments the model can make are offered...
        assertThat(systemPrompt).contains("PRIZE_OR_LOTTERY_BAIT").contains("SUSPICIOUS_LINK");
        // ...but facts established by deterministic checks the model cannot verify are not.
        assertThat(systemPrompt).doesNotContain("KNOWN_BAD_URL").doesNotContain("BURST_OVERRIDE");
        // A small few-shot anchors the schema and grounded vocabulary (AC 1).
        assertThat(systemPrompt).contains("Example response").contains("\"verdict\":\"SPAM\"");
    }

    @Test
    void does_not_call_the_provider_when_the_decision_was_not_routed_is_the_callers_job() {
        // The service only runs when invoked; with no stubbing it must not call the port spuriously.
        service();

        verify(port, never()).complete(anyString(), anyString());
    }

    @Test
    void does_not_call_the_provider_when_the_budget_cap_denies_the_call() {
        // The daily cap is spent: the gate denies before any provider call (story 05.04).
        budget = mock(LlmBudget.class);
        when(budget.tryReserve()).thenReturn(BudgetReservation.denied(BudgetScope.DAILY));

        LlmOutcome outcome = service().classify(email, REASONS);

        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.attempts()).isZero();
        assertThat(outcome.costUsd()).isEqualByComparingTo("0");
        verify(port, never()).complete(anyString(), anyString());
        assertThat(registry.get(LlmMeter.BUDGET_DENIED).tag("scope", "daily").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get(LlmMeter.DEGRADED).tag("reason", "budget").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void fail_degrades_without_calling_the_provider_when_the_budget_store_is_unavailable() {
        // Redis down: the gate fails closed (denies, no scope) rather than spending blind.
        budget = mock(LlmBudget.class);
        when(budget.tryReserve()).thenReturn(BudgetReservation.unavailable());

        LlmOutcome outcome = service().classify(email, REASONS);

        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.attempts()).isZero();
        verify(port, never()).complete(anyString(), anyString());
        // An outage of the cost-control store is recorded as unavailable, not as budget-spent.
        assertThat(registry.get(LlmMeter.DEGRADED).tag("reason", "unavailable").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void reconciles_the_reservation_with_the_actual_cost_after_a_call() {
        budget = mock(LlmBudget.class);
        BudgetReservation reservation =
                BudgetReservation.granted(new BigDecimal("0.01"), "day", "month");
        when(budget.tryReserve()).thenReturn(reservation);
        when(port.complete(anyString(), anyString())).thenReturn(response(validJson()));

        service().classify(email, REASONS);

        // The reservation is trued up to the call's real cost (0.02 USD) so the counter tracks spend.
        verify(budget).reconcile(eq(reservation), eq(new BigDecimal("0.020000")));
    }

    @Test
    void reconciles_with_zero_cost_when_the_provider_was_unavailable() {
        budget = mock(LlmBudget.class);
        BudgetReservation reservation =
                BudgetReservation.granted(new BigDecimal("0.01"), "day", "month");
        when(budget.tryReserve()).thenReturn(reservation);
        when(port.complete(anyString(), anyString()))
                .thenThrow(new LlmUnavailableException("disabled"));

        service().classify(email, REASONS);

        // No call landed, so the whole reservation is released back (reconcile with zero cost).
        verify(budget).reconcile(eq(reservation), eq(BigDecimal.ZERO));
    }
}
