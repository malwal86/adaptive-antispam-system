package com.antispam.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.antispam.decision.Decision;
import com.antispam.seed.GroundTruthLabel;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The simulation orchestration (story 07.02): every decided email yields exactly one event whose
 * conditioning fields echo the stream and whose action is legal for the verdict shown (AC 1/2/4),
 * and a fixed seed reproduces the run (AC 5). Uses the real assembler + sampler over a stubbed
 * persona catalogue, with the event repository mocked to capture what was written.
 */
@ExtendWith(MockitoExtension.class)
class FeedbackSimulationServiceTest {

    @Mock
    private PersonaRepository personaRepository;
    @Mock
    private FeedbackEventRepository eventRepository;
    @Captor
    private ArgumentCaptor<List<FeedbackEvent>> saved;

    private FeedbackSimulationService service() {
        return new FeedbackSimulationService(
                new PersonaPopulationAssembler(personaRepository), new ActionSampler(), eventRepository);
    }

    private static Persona persona(String name, boolean malicious) {
        return new Persona(Persona.idForName(name), name, 0.5, 0.6, 0.5, new PersonaConfig(malicious));
    }

    private static final List<Persona> CATALOGUE =
            List.of(persona("benign", false), persona("bomber", true));

    private static final List<DecidedEmail> STREAM = List.of(
            new DecidedEmail(UUID.randomUUID(), Decision.ALLOW, GroundTruthLabel.SPAM),
            new DecidedEmail(UUID.randomUUID(), Decision.QUARANTINE, GroundTruthLabel.HAM),
            new DecidedEmail(UUID.randomUUID(), Decision.WARN, GroundTruthLabel.PHISH),
            new DecidedEmail(UUID.randomUUID(), Decision.BLOCK, GroundTruthLabel.SPAM));

    private static final PopulationSpec SPEC =
            new PopulationSpec(123L, 20, Map.of("benign", 3, "bomber", 1));

    @Test
    void writes_one_legal_event_per_decided_email_echoing_the_conditioning() {
        when(personaRepository.findAll()).thenReturn(CATALOGUE);

        FeedbackRun run = service().simulate(STREAM, SPEC);

        assertThat(run.eventCount()).isEqualTo(STREAM.size());
        verify(eventRepository).saveAll(saved.capture());
        List<FeedbackEvent> events = saved.getValue();
        assertThat(events).hasSize(STREAM.size());

        for (int i = 0; i < STREAM.size(); i++) {
            DecidedEmail email = STREAM.get(i);
            FeedbackEvent event = events.get(i);
            assertThat(event.emailId()).isEqualTo(email.emailId());
            assertThat(event.decisionShown()).isEqualTo(email.decisionShown());
            assertThat(event.groundTruth()).isEqualTo(email.groundTruth());
            assertThat(event.runId()).isEqualTo(run.runId());
            assertThat(event.action()).isIn(FeedbackAction.spaceFor(email.decisionShown()));
            assertThat(event.source()).isIn("benign", "bomber");
        }
        assertThat(run.countsByAction().values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(STREAM.size());
    }

    @Test
    void a_fixed_seed_reproduces_the_run() {
        when(personaRepository.findAll()).thenReturn(CATALOGUE);
        ArgumentCaptor<List<FeedbackEvent>> twice = ArgumentCaptor.forClass(List.class);

        service().simulate(STREAM, SPEC);
        service().simulate(STREAM, SPEC);

        verify(eventRepository, org.mockito.Mockito.times(2)).saveAll(twice.capture());
        List<FeedbackEvent> first = twice.getAllValues().get(0);
        List<FeedbackEvent> second = twice.getAllValues().get(1);
        assertThat(first).hasSameSizeAs(second);
        for (int i = 0; i < first.size(); i++) {
            assertThat(second.get(i).action()).isEqualTo(first.get(i).action());
            assertThat(second.get(i).confidence()).isEqualTo(first.get(i).confidence());
            assertThat(second.get(i).delaySeconds()).isEqualTo(first.get(i).delaySeconds());
            assertThat(second.get(i).personaId()).isEqualTo(first.get(i).personaId());
        }
    }
}
