package com.antispam.feedback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a persona population over a decision stream and records the sampled feedback (story 07.02).
 * For each decided email it draws a persona from the assembled population and samples that persona's
 * truth-conditioned action ({@link ActionSampler}), writing one {@code feedback_events} row.
 *
 * <p><b>Reproducible (AC 5).</b> A single {@link Random} seeded from the spec drives both the
 * per-email persona draw and the sampler, advanced in a fixed order, so the same {@code (stream,
 * spec)} produces the same actions every run. The run id and event ids identify a particular
 * execution and are not part of that reproducible content.
 *
 * <p><b>Logged-only.</b> It appends feedback events and touches nothing on the live decision path;
 * whether any of this feedback is allowed to move reputation or a retrain label is decided by the
 * weighting/corroboration gate (07.03).
 */
@Service
public class FeedbackSimulationService {

    private final PersonaPopulationAssembler assembler;
    private final ActionSampler sampler;
    private final FeedbackEventRepository events;

    @Autowired
    public FeedbackSimulationService(PersonaPopulationAssembler assembler, ActionSampler sampler,
            FeedbackEventRepository events) {
        this.assembler = assembler;
        this.sampler = sampler;
        this.events = events;
    }

    /**
     * Simulates {@code population} (assembled from {@code spec}) acting on {@code stream}, persists
     * the resulting events as one run, and returns its summary.
     */
    @Transactional
    public FeedbackRun simulate(List<DecidedEmail> stream, PopulationSpec spec) {
        Population population = assembler.assemble(spec);
        Random rng = new Random(spec.seed());
        UUID runId = UUID.randomUUID();

        List<FeedbackEvent> produced = new ArrayList<>(stream.size());
        for (DecidedEmail email : stream) {
            Persona persona = population.members().get(rng.nextInt(population.size()));
            SampledAction sampled = sampler.sample(email, persona, rng);
            produced.add(new FeedbackEvent(
                    UUID.randomUUID(), email.emailId(), persona.id(), runId,
                    sampled.action(), sampled.confidence(), sampled.delaySeconds(),
                    email.decisionShown(), email.groundTruth(), persona.name()));
        }
        events.saveAll(produced);

        Map<FeedbackAction, Long> counts = produced.stream()
                .collect(Collectors.groupingBy(FeedbackEvent::action, Collectors.counting()));
        return new FeedbackRun(runId, produced.size(), counts);
    }
}
