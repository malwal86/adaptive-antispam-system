package com.antispam.arena;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.antispam.ingest.Email;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import com.antispam.ingest.ParsedEmail;
import com.antispam.seed.GroundTruthLabel;
import com.antispam.seed.GroundTruthLabelRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The mutation engine's contract (story 08.01): given a real seed spam it asks the attacker to
 * perturb the seed with the requested strategy, ingests the result as a canonical email off the live
 * spine, and logs an {@code adversarial_emails} row whose lineage points back to the seed, whose
 * label is the seed's (preserved), and whose strategy and attacker model are recorded. It refuses to
 * mutate anything that is not abuse, a seed it cannot find, and a perturbation that changed nothing.
 */
@ExtendWith(MockitoExtension.class)
class MutationServiceTest {

    @Mock
    private com.antispam.ingest.EmailRepository emails;

    @Mock
    private GroundTruthLabelRepository labels;

    @Mock
    private AttackerPort attacker;

    @Mock
    private IngestService ingest;

    @Mock
    private AdversarialEmailRepository variants;

    private MutationService service() {
        return new MutationService(emails, labels, attacker, ingest, variants,
                new ArenaProperties(true, "attacker-x", 3, null, null));
    }

    private static final UUID SEED_ID = UUID.randomUUID();
    private static final UUID VARIANT_ID = UUID.randomUUID();
    private static final String SEED_TEXT = "Subject: free money\n\nClick http://evil.test now";

    @Test
    void mutates_the_seed_and_logs_a_variant_with_strategy_label_and_seed_lineage() {
        seedIs(GroundTruthLabel.SPAM);
        when(attacker.mutate(MutationStrategy.SYNONYM, SEED_TEXT)).thenReturn("Subject: gratis cash\n\nTap http://evil.test now");
        when(ingest.ingestOffSpine(any(), eq("adversarial")))
                .thenReturn(new IngestResult(VARIANT_ID, "hash", false, "adversarial"));
        AdversarialEmail logged = adversarial(MutationStrategy.SYNONYM, GroundTruthLabel.SPAM);
        when(variants.save(VARIANT_ID, SEED_ID, null, MutationStrategy.SYNONYM, GroundTruthLabel.SPAM,
                "attacker-x", null, null)).thenReturn(logged);

        AdversarialEmail result = service().mutate(SEED_ID, MutationStrategy.SYNONYM);

        assertThat(result).isEqualTo(logged);
        verify(variants).save(VARIANT_ID, SEED_ID, null, MutationStrategy.SYNONYM, GroundTruthLabel.SPAM,
                "attacker-x", null, null);
    }

    @Test
    void preserves_a_phish_seeds_label_on_the_variant() {
        seedIs(GroundTruthLabel.PHISH);
        when(attacker.mutate(MutationStrategy.HOMOGLYPH, SEED_TEXT)).thenReturn("Subject: free mοney\n\nClick http://evil.test now");
        when(ingest.ingestOffSpine(any(), eq("adversarial")))
                .thenReturn(new IngestResult(VARIANT_ID, "hash", false, "adversarial"));
        when(variants.save(any(), any(), any(), any(), eq(GroundTruthLabel.PHISH), any(), any(), any()))
                .thenReturn(adversarial(MutationStrategy.HOMOGLYPH, GroundTruthLabel.PHISH));

        service().mutate(SEED_ID, MutationStrategy.HOMOGLYPH);

        verify(variants).save(VARIANT_ID, SEED_ID, null, MutationStrategy.HOMOGLYPH, GroundTruthLabel.PHISH,
                "attacker-x", null, null);
    }

    @Test
    void ingests_the_attackers_output_bytes_as_the_variant_content() {
        seedIs(GroundTruthLabel.SPAM);
        String mutated = "Subject: gratis cash\n\nTap http://evil.test now";
        when(attacker.mutate(MutationStrategy.REFRAME, SEED_TEXT)).thenReturn(mutated);
        when(ingest.ingestOffSpine(any(), eq("adversarial")))
                .thenReturn(new IngestResult(VARIANT_ID, "hash", false, "adversarial"));
        when(variants.save(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(adversarial(MutationStrategy.REFRAME, GroundTruthLabel.SPAM));

        service().mutate(SEED_ID, MutationStrategy.REFRAME);

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(ingest).ingestOffSpine(bytes.capture(), eq("adversarial"));
        assertThat(new String(bytes.getValue(), StandardCharsets.UTF_8)).isEqualTo(mutated);
    }

    @Test
    void rejects_a_seed_that_does_not_exist() {
        when(emails.findById(SEED_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().mutate(SEED_ID, MutationStrategy.SYNONYM))
                .isInstanceOf(MutationException.class);
        verify(attacker, never()).mutate(any(), any());
        verify(variants, never()).save(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejects_a_seed_with_no_ground_truth_label() {
        when(emails.findById(SEED_ID)).thenReturn(Optional.of(seedEmail()));
        when(labels.findByEmailId(SEED_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().mutate(SEED_ID, MutationStrategy.SYNONYM))
                .isInstanceOf(MutationException.class);
        verify(attacker, never()).mutate(any(), any());
    }

    @Test
    void refuses_to_mutate_a_ham_seed_because_this_story_stresses_recall_not_precision() {
        when(emails.findById(SEED_ID)).thenReturn(Optional.of(seedEmail()));
        when(labels.findByEmailId(SEED_ID)).thenReturn(Optional.of(GroundTruthLabel.HAM));

        assertThatThrownBy(() -> service().mutate(SEED_ID, MutationStrategy.SYNONYM))
                .isInstanceOf(MutationException.class);
        verify(attacker, never()).mutate(any(), any());
    }

    @Test
    void rejects_a_no_op_mutation_whose_variant_is_identical_to_the_seed() {
        seedIs(GroundTruthLabel.SPAM);
        when(attacker.mutate(MutationStrategy.STRUCTURE, SEED_TEXT)).thenReturn(SEED_TEXT);

        assertThatThrownBy(() -> service().mutate(SEED_ID, MutationStrategy.STRUCTURE))
                .isInstanceOf(MutationException.class);
        verify(ingest, never()).ingestOffSpine(any(), any());
        verify(variants, never()).save(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejects_a_blank_mutation_from_the_attacker() {
        seedIs(GroundTruthLabel.SPAM);
        when(attacker.mutate(MutationStrategy.SYNONYM, SEED_TEXT)).thenReturn("   ");

        assertThatThrownBy(() -> service().mutate(SEED_ID, MutationStrategy.SYNONYM))
                .isInstanceOf(MutationException.class);
        verify(ingest, never()).ingestOffSpine(any(), any());
    }

    private void seedIs(GroundTruthLabel label) {
        when(emails.findById(SEED_ID)).thenReturn(Optional.of(seedEmail()));
        when(labels.findByEmailId(SEED_ID)).thenReturn(Optional.of(label));
    }

    private static Email seedEmail() {
        return new Email(SEED_ID, new byte[32], SEED_TEXT.getBytes(StandardCharsets.UTF_8),
                new ParsedEmail("spammer@evil.test", "evil.test", null, "free money", null, null),
                "seed", Instant.EPOCH);
    }

    private static AdversarialEmail adversarial(MutationStrategy strategy, GroundTruthLabel label) {
        return new AdversarialEmail(UUID.randomUUID(), VARIANT_ID, SEED_ID, null, strategy, label,
                "attacker-x", null, null, Instant.EPOCH);
    }
}
