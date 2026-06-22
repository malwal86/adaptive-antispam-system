package com.antispam.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.antispam.decision.calibration.ActiveCalibrator;
import com.antispam.decision.calibration.ProbabilityCalibrator;
import com.antispam.decision.model.ModelMetadata;
import com.antispam.decision.model.OnnxModel;
import com.antispam.ingest.Email;
import com.antispam.reputation.BetaReputation;
import com.antispam.reputation.ReputationService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The fusion stage's orchestration contract (story 04.04): it enforces calibration as a
 * hard precondition (AC 3), reads the sender's prior from the auth-gated view, and feeds
 * the abuse prior ({@code 1 − trust mean}), Beta variance, calibrated confidence, and the
 * model's {@code π_train} into {@link LogOddsFusion}. The fusion numerics themselves are
 * pinned in {@link LogOddsFusionTest}; here we check the wiring.
 */
@ExtendWith(MockitoExtension.class)
class FusionServiceTest {

    @Mock
    private ReputationService reputation;

    private final ModelMetadata modelMetadata = new ModelMetadata();
    private ActiveCalibrator calibrator;
    private FusionService fusionService;

    private static final ModelScores SCORES =
            new ModelScores(0.5, 0.3, OnnxModel.MODEL_VERSION, 0.8);

    @BeforeEach
    void setUp() {
        calibrator = new ActiveCalibrator();
        fusionService = new FusionService(reputation, modelMetadata, calibrator);
    }

    @Test
    void blocks_fusion_when_no_calibration_is_installed() {
        // Default ActiveCalibrator is the identity and reports uncalibrated.
        Email email = TestEmails.from("sender.test", "Authentication-Results: mx; dmarc=pass");

        Optional<FusedScore> fused = fusionService.fuse(email, SCORES);

        assertThat(fused).isEmpty();
        // It must not even read reputation: the precondition fails before any input is gathered.
        verifyNoInteractions(reputation);
    }

    @Test
    void fuses_the_calibrated_score_with_the_authenticated_prior() {
        calibrator.install(ProbabilityCalibrator.identity());
        // good=8, bad=2 → trust mean 0.75, so abuse prior 0.25, with the Beta's variance.
        BetaReputation rep = new BetaReputation(8, 2, 1, 1);
        when(reputation.reputationFor(anyString(), eq(true))).thenReturn(rep);
        Email email = TestEmails.from("good.test", "Authentication-Results: mx; dmarc=pass");

        FusedScore fused = fusionService.fuse(email, SCORES).orElseThrow();

        FusedScore expected = LogOddsFusion.fuse(
                1.0 - rep.mean(), rep.variance(), 0.8, modelMetadata.trainingBaseRate(OnnxModel.MODEL_VERSION));
        assertThat(fused.posterior()).isCloseTo(expected.posterior(), within(1e-12));
        assertThat(fused.uncertaintyBand()).isCloseTo(expected.uncertaintyBand(), within(1e-12));
    }

    @Test
    void reads_the_unauthenticated_view_for_an_unaligned_email() {
        calibrator.install(ProbabilityCalibrator.identity());
        when(reputation.reputationFor(anyString(), eq(false))).thenReturn(new BetaReputation(1, 1, 1, 1));
        Email email = TestEmails.from("spoofed.test", "Authentication-Results: mx; dmarc=fail");

        fusionService.fuse(email, SCORES);

        // A spoofed (unaligned) mail must be scored on the capped unauthenticated bucket.
        verify(reputation).reputationFor(anyString(), eq(false));
        verify(reputation, never()).reputationFor(anyString(), eq(true));
    }
}
