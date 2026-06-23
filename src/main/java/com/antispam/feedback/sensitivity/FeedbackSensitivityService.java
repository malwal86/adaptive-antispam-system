package com.antispam.feedback.sensitivity;

import com.antispam.decision.ClassificationRepository;
import com.antispam.decision.Decision;
import com.antispam.decision.DecisionOutcome;
import com.antispam.decision.RouteUsed;
import com.antispam.feedback.DecidedEmail;
import com.antispam.feedback.FeedbackRun;
import com.antispam.feedback.FeedbackSimulationService;
import com.antispam.feedback.Persona;
import com.antispam.feedback.PersonaConfig;
import com.antispam.feedback.PersonaRepository;
import com.antispam.feedback.PopulationSpec;
import com.antispam.feedback.gate.FeedbackGateProperties;
import com.antispam.feedback.gate.FeedbackGateService;
import com.antispam.ingest.IngestService;
import com.antispam.reputation.ReputationSignal;
import com.antispam.seed.GroundTruthLabel;
import com.antispam.seed.GroundTruthLabelRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The malicious-fraction sensitivity sweep (story 07.04): proves the feedback defence (the 07.03
 * gate) holds as the adversarial population grows, and reports where it breaks. For each malicious
 * fraction it assembles a fresh population — good-faith readers plus distinct report/rescue bombers
 * ({@link MaliciousMix}) — runs them over a controlled stream (a legitimate sender's delivered ham
 * and a spam sender's quarantined mail) through the real simulator (07.02) and gate (07.03), then
 * measures how far the bombers moved each sender's reputation.
 *
 * <p><b>Why the attack is structurally blunted.</b> The gate down-weights every malicious report to
 * {@code maliciousTrust} and only lets a group move state once its summed weight clears
 * {@code minWeight} ({@link FeedbackGateProperties}). So however many delivered hams a clutch of
 * report bombers floods, their aggregate weight is {@code bombers × maliciousTrust × confidence},
 * which cannot reach {@code minWeight} until there are {@code ceil(minWeight / maliciousTrust)}
 * <em>distinct</em> bombers on one vector — the analytical breakdown the report documents. Below it
 * the drift is exactly zero; this is a property of the gate, not of a particular random draw, so the
 * sweep's "blunted" result is robust rather than seed-lucky.
 *
 * <p><b>Reproducible and side-effect-scoped.</b> Each point runs at {@code baseSeed + index}, so the
 * curve is reproducible (AC 4); each point also uses freshly minted, token-scoped senders and
 * personas so points never contaminate each other's reputation in the shared, append-only log. The
 * sweep reads existing pipeline state only through the public simulator/gate — it mints its own
 * controlled corpus rather than mutating the live one.
 */
@Service
public class FeedbackSensitivityService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackSensitivityService.class);

    /** Net feedback-driven reputation weight for a sender, grouped by signal, source='feedback'. */
    private static final String DRIFT_SQL = """
            select signal, coalesce(sum(weight), 0) as total
            from reputation_events
            where sender_key = ? and source = 'feedback'
            group by signal
            """;

    private final PersonaRepository personas;
    private final IngestService ingest;
    private final ClassificationRepository classifications;
    private final GroundTruthLabelRepository groundTruth;
    private final FeedbackSimulationService simulator;
    private final FeedbackGateService gate;
    private final JdbcTemplate jdbc;
    private final FeedbackGateProperties gateProps;
    private final FeedbackSensitivityProperties props;

    @Autowired
    public FeedbackSensitivityService(
            PersonaRepository personas,
            IngestService ingest,
            ClassificationRepository classifications,
            GroundTruthLabelRepository groundTruth,
            FeedbackSimulationService simulator,
            FeedbackGateService gate,
            JdbcTemplate jdbc,
            FeedbackGateProperties gateProps,
            FeedbackSensitivityProperties props) {
        this.personas = personas;
        this.ingest = ingest;
        this.classifications = classifications;
        this.groundTruth = groundTruth;
        this.simulator = simulator;
        this.gate = gate;
        this.jdbc = jdbc;
        this.gateProps = gateProps;
        this.props = props;
    }

    /** Runs the full sweep and returns the curve plus the breakdown summary. */
    public SensitivityReport sweep(SweepSpec spec) {
        int breakdownBomberCount = (int) Math.ceil(gateProps.minWeight() / gateProps.maliciousTrust());
        List<SensitivityPoint> points = new ArrayList<>(spec.maliciousFractions().size());
        for (int index = 0; index < spec.maliciousFractions().size(); index++) {
            points.add(runPoint(spec, index, spec.maliciousFractions().get(index)));
        }
        log.info("sensitivity sweep populationSize={} fractions={} -> breakdownBomberCount(per vector)={}",
                spec.populationSize(), spec.maliciousFractions(), breakdownBomberCount);
        return SensitivityReport.from(points, props.driftTolerance(), breakdownBomberCount);
    }

    /** Runs one malicious-fraction point: build population + stream, simulate, gate, measure. */
    private SensitivityPoint runPoint(SweepSpec spec, int index, double fraction) {
        MaliciousMix mix = MaliciousMix.forFraction(spec.populationSize(), fraction);
        String token = UUID.randomUUID().toString().substring(0, 12);

        Map<String, Integer> weights = seedPopulation(mix, token);
        String legitSender = "ham-" + token + "@legit.test";
        String spamSender = "spam-" + token + "@bad.test";
        List<DecidedEmail> stream = buildStream(spec.streamPerSender(), token, legitSender, spamSender);

        FeedbackRun run = simulator.simulate(stream, new PopulationSpec(spec.baseSeed() + index, mix.populationSize(), weights));
        gate.gate(run.runId());

        double hamDrift = harmfulWeight(legitSender, ReputationSignal.BAD);    // report bombers push BAD
        double spamPromotion = harmfulWeight(spamSender, ReputationSignal.GOOD); // rescue bombers push GOOD
        boolean blunted = hamDrift <= props.driftTolerance() && spamPromotion <= props.driftTolerance();
        return new SensitivityPoint(fraction, spec.populationSize(), mix.bomberCount(), hamDrift, spamPromotion, blunted);
    }

    /** Seeds {@code mix} as distinct, token-scoped personas and returns the equal-weight population spec. */
    private Map<String, Integer> seedPopulation(MaliciousMix mix, String token) {
        List<Persona> roster = new ArrayList<>();
        Map<String, Integer> weights = new LinkedHashMap<>();
        // Good-faith readers: open mail, almost never report legit mail, cautious with risky mail.
        addPersonas(roster, weights, mix.genuine(), "sweep-" + token + "-g-", 0.70, 0.05, 0.30, false);
        // Report bombers: mass-report whatever was delivered (mirrors the catalogue's report-bomber).
        addPersonas(roster, weights, mix.reportBombers(), "sweep-" + token + "-rb-", 0.05, 0.98, 0.10, true);
        // Rescue bombers: mass-rescue whatever was withheld (mirrors the catalogue's rescue-bomber).
        addPersonas(roster, weights, mix.rescueBombers(), "sweep-" + token + "-xb-", 0.90, 0.02, 0.95, true);
        personas.seed(roster);
        return weights;
    }

    private static void addPersonas(List<Persona> roster, Map<String, Integer> weights, int count,
            String prefix, double click, double report, double risk, boolean malicious) {
        for (int i = 0; i < count; i++) {
            String name = prefix + String.format("%03d", i);
            roster.add(new Persona(Persona.idForName(name), name, click, report, risk, new PersonaConfig(malicious)));
            weights.put(name, 1);
        }
    }

    /** A delivered-ham + withheld-spam stream from the two scenario senders, both decided and labeled. */
    private List<DecidedEmail> buildStream(int perSender, String token, String legitSender, String spamSender) {
        List<DecidedEmail> stream = new ArrayList<>(perSender * 2);
        for (int i = 0; i < perSender; i++) {
            UUID ham = decidedEmail(legitSender, true, Decision.ALLOW, GroundTruthLabel.HAM,
                    "your weekly newsletter " + token + " " + i);
            stream.add(new DecidedEmail(ham, Decision.ALLOW, GroundTruthLabel.HAM));
            UUID spam = decidedEmail(spamSender, false, Decision.QUARANTINE, GroundTruthLabel.SPAM,
                    "you have won a prize claim now " + token + " " + i);
            stream.add(new DecidedEmail(spam, Decision.QUARANTINE, GroundTruthLabel.SPAM));
        }
        return stream;
    }

    /** Ingests one decided, labeled email from {@code sender}; {@code dmarcPass} sets the accrual bucket. */
    private UUID decidedEmail(String sender, boolean dmarcPass, Decision decision, GroundTruthLabel label, String body) {
        StringBuilder raw = new StringBuilder()
                .append("From: ").append(sender).append("\r\n")
                .append("Subject: sweep\r\n");
        if (dmarcPass) {
            raw.append("Authentication-Results: mx.test; dmarc=pass\r\n");
        }
        raw.append("\r\n").append(body);
        UUID emailId = ingest.ingest(raw.toString().getBytes(StandardCharsets.UTF_8), "sensitivity-sweep").emailId();
        classifications.save(emailId, new DecisionOutcome(decision, List.of(), RouteUsed.MODEL, 1L), null, null, null);
        groundTruth.saveIfAbsent(emailId, label, "sensitivity-sweep");
        return emailId;
    }

    /**
     * The feedback-driven reputation weight a sender accrued in the attack's <em>harmful</em>
     * direction — the attack's footprint after the gate. For the legit sender that is {@link
     * ReputationSignal#BAD} weight (report bombing trying to down-rep it); for the spam sender it is
     * {@link ReputationSignal#GOOD} weight (rescue bombing trying to promote it). It is deliberately
     * one-directional, not net: a legit sender legitimately earning GOOD from genuine clicks is the
     * system working, not the attack — so only the harmful weight measures whether the attack landed.
     * Zero means the attack moved nothing, which is exactly what the gate guarantees below its
     * weight-floor breakdown.
     */
    private double harmfulWeight(String sender, ReputationSignal harmful) {
        Map<String, Double> bySignal = new java.util.HashMap<>();
        jdbc.query(DRIFT_SQL, rs -> {
            bySignal.put(rs.getString("signal"), rs.getDouble("total"));
        }, sender);
        return bySignal.getOrDefault(harmful.name(), 0.0);
    }
}
