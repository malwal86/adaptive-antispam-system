package com.antispam.reputation.accrual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.antispam.decision.Decision;
import com.antispam.decision.DecisionOutcome;
import com.antispam.decision.DecisionService;
import com.antispam.decision.RouteUsed;
import com.antispam.idempotency.ProcessedMessageLedger;
import com.antispam.ingest.Email;
import com.antispam.ingest.EmailRepository;
import com.antispam.ingest.ParsedEmail;
import com.antispam.reputation.ReputationBucket;
import com.antispam.reputation.ReputationService;
import com.antispam.reputation.ReputationSignal;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The accrual seam in isolation (story 03.05): given an email id and the partition
 * its event arrived on, it loads the email, derives the verdict and auth bucket,
 * and records exactly one reputation signal — idempotently. These tests pin the
 * derivation (verdict to signal, auth to bucket), the at-least-once contract
 * (duplicate and unknown-email deliveries record nothing), and — the heart of the
 * story — that the updater carries no application lock and no shared mutable
 * per-sender state, so correctness rests on Kafka's per-partition serialization
 * alone.
 */
@ExtendWith(MockitoExtension.class)
class ReputationAccrualServiceTest {

    private static final int PARTITION = 3;

    @Mock
    private EmailRepository emails;

    @Mock
    private DecisionService decisionService;

    @Mock
    private ReputationService reputationService;

    @Mock
    private ProcessedMessageLedger ledger;

    @Mock
    private PartitionSkewMeter skewMeter;

    private ReputationAccrualService service;

    @BeforeEach
    void setUp() {
        service = new ReputationAccrualService(
                emails, decisionService, reputationService, ledger, skewMeter);
    }

    @Test
    void a_first_delivery_of_an_authenticated_allow_records_a_good_authenticated_signal() {
        UUID id = UUID.randomUUID();
        Email email = emailFrom(id, "alice@good.example", "good.example", "spf=pass; dkim=pass; dmarc=pass");
        when(emails.findById(id)).thenReturn(Optional.of(email));
        when(ledger.claim(ReputationAccrualService.CONSUMER_GROUP, id.toString())).thenReturn(true);
        when(decisionService.evaluate(email)).thenReturn(outcome(Decision.ALLOW));

        boolean accrued = service.accrue(id, PARTITION);

        assertThat(accrued).isTrue();
        verify(reputationService).record(
                "alice@good.example", // SenderKey.of prefers the full From address over the domain
                ReputationSignal.GOOD,
                ReputationAccrualService.SIGNAL_WEIGHT,
                ReputationAccrualService.SIGNAL_SOURCE,
                ReputationBucket.AUTHENTICATED);
        verify(skewMeter).record(PARTITION);
    }

    @Test
    void a_block_verdict_records_a_bad_signal() {
        UUID id = UUID.randomUUID();
        Email email = emailFrom(id, "evil@bad.example", "bad.example", "spf=pass; dkim=pass; dmarc=pass");
        when(emails.findById(id)).thenReturn(Optional.of(email));
        when(ledger.claim(anyString(), eq(id.toString()))).thenReturn(true);
        when(decisionService.evaluate(email)).thenReturn(outcome(Decision.BLOCK));

        service.accrue(id, PARTITION);

        verify(reputationService).record(
                anyString(), eq(ReputationSignal.BAD), anyDouble(), anyString(), any());
    }

    @Test
    void an_unauthenticated_email_accrues_to_the_unauthenticated_bucket() {
        UUID id = UUID.randomUUID();
        Email email = emailFrom(id, "spoof@bank.example", "bank.example", "spf=fail; dkim=fail; dmarc=fail");
        when(emails.findById(id)).thenReturn(Optional.of(email));
        when(ledger.claim(anyString(), eq(id.toString()))).thenReturn(true);
        when(decisionService.evaluate(email)).thenReturn(outcome(Decision.ALLOW));

        service.accrue(id, PARTITION);

        verify(reputationService).record(
                anyString(), any(), anyDouble(), anyString(), eq(ReputationBucket.UNAUTHENTICATED));
    }

    @Test
    void a_duplicate_delivery_records_nothing() {
        UUID id = UUID.randomUUID();
        Email email = emailFrom(id, "alice@good.example", "good.example", "dmarc=pass");
        when(emails.findById(id)).thenReturn(Optional.of(email));
        when(ledger.claim(anyString(), eq(id.toString()))).thenReturn(false);

        boolean accrued = service.accrue(id, PARTITION);

        assertThat(accrued).isFalse();
        verifyNoInteractions(reputationService);
        verify(skewMeter, never()).record(anyInt());
    }

    @Test
    void an_unknown_email_is_skipped_without_claiming_or_recording() {
        UUID id = UUID.randomUUID();
        when(emails.findById(id)).thenReturn(Optional.empty());

        boolean accrued = service.accrue(id, PARTITION);

        assertThat(accrued).isFalse();
        verifyNoInteractions(ledger);
        verifyNoInteractions(reputationService);
        verify(skewMeter, never()).record(anyInt());
    }

    @Test
    void the_accrual_updater_holds_no_application_lock_and_no_shared_mutable_state() {
        // AC 1 / AC 5: correctness comes from per-partition serialization, not mutexes.
        // The updater must therefore declare no synchronized method, hold no lock, and
        // keep no mutable per-sender state — every field is an injected, final collaborator.
        for (Method method : ReputationAccrualService.class.getDeclaredMethods()) {
            assertThat(Modifier.isSynchronized(method.getModifiers()))
                    .as("method %s must not be synchronized", method.getName())
                    .isFalse();
        }
        for (Field field : ReputationAccrualService.class.getDeclaredFields()) {
            assertThat(Lock.class.isAssignableFrom(field.getType()))
                    .as("field %s must not be a Lock", field.getName())
                    .isFalse();
            assertThat(Modifier.isFinal(field.getModifiers()))
                    .as("field %s must be final (no mutable shared state)", field.getName())
                    .isTrue();
        }
    }

    private static DecisionOutcome outcome(Decision decision) {
        return new DecisionOutcome(decision, List.of(), RouteUsed.MODEL, 0L);
    }

    private static Email emailFrom(UUID id, String sender, String domain, String authResults) {
        ParsedEmail metadata = new ParsedEmail(sender, domain, null, "subject", null, authResults);
        return new Email(id, new byte[] {1}, new byte[] {2}, metadata, "api", Instant.parse("2026-06-21T00:00:00Z"));
    }
}
