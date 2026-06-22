package com.antispam.decision.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.antispam.decision.ModelScores;
import com.antispam.features.FeatureSet;
import com.antispam.features.FeatureSet.AuthFeatures;
import com.antispam.features.FeatureSet.HeaderFeatures;
import com.antispam.features.FeatureSet.LinkFeatures;
import com.antispam.features.FeatureSet.TextFeatures;
import com.antispam.features.FeatureSet.TimingFeatures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Functional contract of the in-process classifier: it loads the checked-in model,
 * returns well-formed probabilities, and separates obvious spam/phish from calm
 * ham. These are behavioral (metamorphic) checks — exact-value fidelity to the
 * Python source model is asserted separately in {@link OnnxModelParityTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnnxModelTest {

    private OnnxModel model;

    @BeforeAll
    void loadModel() {
        // Loads from the classpath, no Spring context needed — the same path the
        // bean takes at startup.
        model = new OnnxModel();
    }

    @AfterAll
    void close() throws Exception {
        model.close();
    }

    private static float[] vector(FeatureSet features) {
        return ModelFeatureVector.toVector(features);
    }

    /** A calm, well-formed personal email: no shouting, one link, auth passing. */
    private static FeatureSet calmHam() {
        return new FeatureSet(
                new HeaderFeatures(true, 42, 0.05, 0, true, 1, false),
                new LinkFeatures(1, 1, false, false, 55),
                new TextFeatures(800, 140, 0.04, 0, 5.0),
                new TimingFeatures(true, 14, 3, false),
                new AuthFeatures("pass", "pass", "pass"),
                null);
    }

    /** A shouty bulk blast: salesy subject, many links, weak auth. */
    private static FeatureSet shoutySpam() {
        return new FeatureSet(
                new HeaderFeatures(true, 88, 0.7, 5, true, 1, true),
                new LinkFeatures(9, 6, false, false, 160),
                new TextFeatures(2200, 380, 0.6, 8, 5.3),
                new TimingFeatures(true, 2, 7, true),
                new AuthFeatures("none", "fail", "fail"),
                null);
    }

    /** A credential-theft attempt: raw-IP + punycode links, mismatched Reply-To, DMARC fail. */
    private static FeatureSet ipPhish() {
        return new FeatureSet(
                new HeaderFeatures(true, 60, 0.3, 1, true, 1, true),
                new LinkFeatures(3, 2, true, true, 220),
                new TextFeatures(500, 90, 0.15, 2, 5.0),
                new TimingFeatures(true, 3, 4, false),
                new AuthFeatures("fail", "fail", "fail"),
                null);
    }

    @Test
    void returns_probabilities_in_the_unit_interval() {
        ModelScores scores = model.score(vector(calmHam()));
        assertThat(scores.spamScore()).isBetween(0.0, 1.0);
        assertThat(scores.phishingScore()).isBetween(0.0, 1.0);
        // 3-class probabilities sum to 1, so spam + phish can never exceed it.
        assertThat(scores.spamScore() + scores.phishingScore()).isLessThanOrEqualTo(1.0 + 1e-6);
    }

    @Test
    void stamps_the_served_model_version() {
        assertThat(model.score(vector(calmHam())).modelVersion()).isEqualTo(OnnxModel.MODEL_VERSION);
    }

    @Test
    void scores_shouty_spam_higher_on_spam_than_calm_ham() {
        double spammy = model.score(vector(shoutySpam())).spamScore();
        double calm = model.score(vector(calmHam())).spamScore();
        assertThat(spammy).isGreaterThan(calm);
        assertThat(spammy).isGreaterThan(0.5);
    }

    @Test
    void scores_an_ip_phish_higher_on_phishing_than_on_spam() {
        ModelScores scores = model.score(vector(ipPhish()));
        assertThat(scores.phishingScore()).isGreaterThan(scores.spamScore());
        assertThat(scores.phishingScore()).isGreaterThan(0.5);
    }

    @Test
    void rejects_a_null_vector() {
        assertThatThrownBy(() -> model.score(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_a_wrong_length_vector() {
        assertThatThrownBy(() -> model.score(new float[10]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
