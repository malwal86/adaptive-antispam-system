package com.antispam.decision.model;

import com.antispam.features.EmailFeatureExtractor;
import com.antispam.features.FeatureSet;
import com.antispam.features.FeatureSet.AuthFeatures;
import com.antispam.features.FeatureSet.HeaderFeatures;
import com.antispam.features.FeatureSet.LinkFeatures;
import com.antispam.features.FeatureSet.TextFeatures;
import com.antispam.features.FeatureSet.TimingFeatures;

/**
 * The model-input contract: flattens a {@link FeatureSet} into the fixed-order
 * {@code float[]} the ONNX classifier consumes (story 04.01 AC 5). The order and
 * encoding here are co-defined with the Python training side in
 * {@code ml/feature_schema.py} — they MUST stay identical, since the model was fit
 * on vectors in exactly this layout. The Java↔Python parity test
 * ({@code OnnxModelParityTest}) and this class's unit test guard that agreement.
 *
 * <p><b>Versioning.</b> The layout is bound to
 * {@link EmailFeatureExtractor#FEATURE_VERSION}: a model trained against this
 * vector is only valid for features extracted at that version. Changing the layout
 * (adding, reordering, or re-encoding a feature) is a feature-version bump and a
 * model retrain together, never one silently.
 *
 * <p>The {@link FeatureSet#embedding()} is deliberately excluded — it is null until
 * Epic 04.03 and is not part of this model's input.
 */
public final class ModelFeatureVector {

    /** Number of features in the vector; the model's input dimension. */
    public static final int FEATURE_COUNT = 24;

    private ModelFeatureVector() {
    }

    /**
     * Flattens {@code features} into the model input vector. Every element is a
     * {@code float}; booleans map to {@code 1.0f}/{@code 0.0f}, an absent timestamp
     * maps its hour/day to {@code -1.0f}, and each auth token is encoded ordinally
     * by {@link #authScore(String)}.
     *
     * @return a new {@code float[}{@value #FEATURE_COUNT}{@code ]} in the documented order
     * @throws IllegalArgumentException if {@code features} is null
     */
    public static float[] toVector(FeatureSet features) {
        if (features == null) {
            throw new IllegalArgumentException("features must not be null");
        }
        HeaderFeatures h = features.header();
        LinkFeatures l = features.link();
        TextFeatures t = features.text();
        TimingFeatures tm = features.timing();
        AuthFeatures a = features.auth();

        float[] v = new float[FEATURE_COUNT];
        // -- header (0..6) --
        v[0] = bit(h.hasSubject());
        v[1] = h.subjectLength();
        v[2] = (float) h.subjectUppercaseRatio();
        v[3] = h.subjectExclamationCount();
        v[4] = bit(h.hasSender());
        v[5] = h.recipientCount();
        v[6] = bit(h.replyToDiffersFromFrom());
        // -- link (7..11) --
        v[7] = l.urlCount();
        v[8] = l.uniqueDomainCount();
        v[9] = bit(l.hasIpUrl());
        v[10] = bit(l.hasPunycodeDomain());
        v[11] = l.maxUrlLength();
        // -- text (12..16) --
        v[12] = t.charCount();
        v[13] = t.wordCount();
        v[14] = (float) t.uppercaseRatio();
        v[15] = t.exclamationCount();
        v[16] = (float) t.avgWordLength();
        // -- timing (17..20); hour/day are -1 when no parseable Date was present --
        v[17] = bit(tm.hasDate());
        v[18] = tm.hourOfDayUtc() == null ? -1.0f : tm.hourOfDayUtc();
        v[19] = tm.dayOfWeek() == null ? -1.0f : tm.dayOfWeek();
        v[20] = bit(tm.weekend());
        // -- auth (21..23) --
        v[21] = authScore(a.spf());
        v[22] = authScore(a.dkim());
        v[23] = authScore(a.dmarc());
        return v;
    }

    /**
     * Ordinal encoding of an {@code Authentication-Results} token: a {@code pass}
     * earns trust ({@code +1}), {@code fail}/{@code softfail} lose it
     * ({@code -1}/{@code -0.5}), and everything unasserted or neutral is {@code 0}.
     * Mirrors {@code AUTH_ENCODING} in {@code ml/feature_schema.py}.
     */
    static float authScore(String token) {
        if (token == null) {
            return 0.0f;
        }
        return switch (token) {
            case "pass" -> 1.0f;
            case "softfail" -> -0.5f;
            case "fail" -> -1.0f;
            default -> 0.0f; // neutral / none / temperror / permerror / policy / unknown
        };
    }

    private static float bit(boolean b) {
        return b ? 1.0f : 0.0f;
    }
}
