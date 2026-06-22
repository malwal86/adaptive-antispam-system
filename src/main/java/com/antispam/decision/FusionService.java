package com.antispam.decision;

import com.antispam.decision.calibration.ActiveCalibrator;
import com.antispam.decision.model.ModelMetadata;
import com.antispam.event.SenderKey;
import com.antispam.features.EmailFeatureExtractor;
import com.antispam.ingest.Email;
import com.antispam.reputation.AuthGate;
import com.antispam.reputation.BetaReputation;
import com.antispam.reputation.ReputationService;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The fusion stage of the decision pipeline (story 04.04): it takes the calibrated
 * model score a mail earned on the model route and combines it with the sender's
 * reputation prior into a single posterior, in log-odds with the training base rate
 * subtracted once (see {@link LogOddsFusion}). The numerics live in {@code LogOddsFusion};
 * this service's job is to assemble fusion's three inputs from the running system and to
 * enforce fusion's one hard precondition.
 *
 * <p><b>Calibration is a hard dependency.</b> Fusing two numbers in log-odds is only
 * meaningful if each is an honest probability; a raw, uncalibrated model score is not
 * (PRD §Subsystem 2). So when no calibration has been installed yet, this stage
 * <em>refuses to fuse</em> — it returns {@link Optional#empty()} and logs why, rather
 * than silently producing a confident-looking-but-meaningless posterior. The dependency
 * is surfaced, not papered over.
 *
 * <p><b>Reputation is read on the gated view.</b> The prior is taken from the
 * authenticated bucket only when the mail proved DMARC alignment, otherwise from the
 * neutral-capped unauthenticated bucket (story 03.03) — so a spoofed message cannot
 * borrow a warmed-up domain's trust as its prior. Reputation's {@link BetaReputation#mean()}
 * is the sender's <em>trust</em> (P of being good), so the abuse prior fusion needs is its
 * complement {@code 1 − mean}; the Beta {@link BetaReputation#variance() variance} carries
 * straight through to the uncertainty band.
 */
@Service
public class FusionService {

    private static final Logger log = LoggerFactory.getLogger(FusionService.class);

    private final ReputationService reputation;
    private final ModelMetadata modelMetadata;
    private final ActiveCalibrator calibrator;

    @Autowired
    public FusionService(ReputationService reputation, ModelMetadata modelMetadata,
            ActiveCalibrator calibrator) {
        this.reputation = reputation;
        this.modelMetadata = modelMetadata;
        this.calibrator = calibrator;
    }

    /**
     * Fuses {@code scores} for {@code email} with the sender's current reputation prior.
     *
     * @return the fused posterior and uncertainty band, or {@link Optional#empty()} when
     *         fusion is blocked because no calibration is installed (the served confidence
     *         is then a raw score fusion must not consume)
     */
    public Optional<FusedScore> fuse(Email email, ModelScores scores) {
        if (!calibrator.isCalibrated()) {
            // Hard dependency surfaced (AC 3): fusing a raw score with reputation would be
            // mathematically meaningless, so we decline rather than emit a bogus posterior.
            log.warn("fusion skipped for email={}: no calibration installed; "
                    + "served confidence is a raw score, not a calibrated probability", email.id());
            return Optional.empty();
        }

        String senderKey = SenderKey.of(email.metadata().sender(), email.metadata().senderDomain());
        boolean dmarcAligned = AuthGate.dmarcAligned(
                EmailFeatureExtractor.authFeatures(email.metadata().authResults()));
        BetaReputation rep = reputation.reputationFor(senderKey, dmarcAligned);

        double reputationPrior = 1.0 - rep.mean();
        double piTrain = modelMetadata.trainingBaseRate(scores.modelVersion());
        FusedScore fused = LogOddsFusion.fuse(
                reputationPrior, rep.variance(), scores.calibratedConfidence(), piTrain);

        log.debug("fused email={} sender={} prior={} model={} piTrain={} -> posterior={} band={}",
                email.id(), senderKey, reputationPrior, scores.calibratedConfidence(), piTrain,
                fused.posterior(), fused.uncertaintyBand());
        return Optional.of(fused);
    }
}
