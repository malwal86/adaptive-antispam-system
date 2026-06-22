package com.antispam.decision.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.antispam.features.FeatureSet;
import com.antispam.features.FeatureSet.AuthFeatures;
import com.antispam.features.FeatureSet.HeaderFeatures;
import com.antispam.features.FeatureSet.LinkFeatures;
import com.antispam.features.FeatureSet.TextFeatures;
import com.antispam.features.FeatureSet.TimingFeatures;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins the model-input contract: the exact length, order, and per-feature encoding
 * of the vector the ONNX classifier consumes. This is the Java half of the
 * cross-language agreement with {@code ml/feature_schema.py}; if either side drifts,
 * the parity test fails — but these assertions localize a Java-side drift to the
 * offending index.
 */
class ModelFeatureVectorTest {

    /** A fully-populated, unambiguous feature set with a distinct value per field. */
    private static FeatureSet fullFeatures() {
        return new FeatureSet(
                new HeaderFeatures(true, 42, 0.25, 3, true, 4, true),
                new LinkFeatures(7, 5, true, true, 123),
                new TextFeatures(800, 140, 0.5, 9, 5.5),
                new TimingFeatures(true, 14, 3, false),
                new AuthFeatures("pass", "fail", "softfail"),
                null);
    }

    @Test
    void produces_a_vector_of_the_fixed_model_input_length() {
        assertThat(ModelFeatureVector.toVector(fullFeatures()))
                .hasSize(ModelFeatureVector.FEATURE_COUNT);
        assertThat(ModelFeatureVector.FEATURE_COUNT).isEqualTo(24);
    }

    @Test
    void maps_each_feature_to_its_documented_index() {
        float[] v = ModelFeatureVector.toVector(fullFeatures());
        // header
        assertThat(v[0]).isEqualTo(1.0f);     // hasSubject
        assertThat(v[1]).isEqualTo(42.0f);    // subjectLength
        assertThat(v[2]).isEqualTo(0.25f);    // subjectUppercaseRatio
        assertThat(v[3]).isEqualTo(3.0f);     // subjectExclamationCount
        assertThat(v[4]).isEqualTo(1.0f);     // hasSender
        assertThat(v[5]).isEqualTo(4.0f);     // recipientCount
        assertThat(v[6]).isEqualTo(1.0f);     // replyToDiffersFromFrom
        // link
        assertThat(v[7]).isEqualTo(7.0f);     // urlCount
        assertThat(v[8]).isEqualTo(5.0f);     // uniqueDomainCount
        assertThat(v[9]).isEqualTo(1.0f);     // hasIpUrl
        assertThat(v[10]).isEqualTo(1.0f);    // hasPunycodeDomain
        assertThat(v[11]).isEqualTo(123.0f);  // maxUrlLength
        // text
        assertThat(v[12]).isEqualTo(800.0f);  // charCount
        assertThat(v[13]).isEqualTo(140.0f);  // wordCount
        assertThat(v[14]).isEqualTo(0.5f);    // uppercaseRatio
        assertThat(v[15]).isEqualTo(9.0f);    // exclamationCount
        assertThat(v[16]).isEqualTo(5.5f);    // avgWordLength
        // timing
        assertThat(v[17]).isEqualTo(1.0f);    // hasDate
        assertThat(v[18]).isEqualTo(14.0f);   // hourOfDayUtc
        assertThat(v[19]).isEqualTo(3.0f);    // dayOfWeek
        assertThat(v[20]).isEqualTo(0.0f);    // weekend
        // auth
        assertThat(v[21]).isEqualTo(1.0f);    // spf pass
        assertThat(v[22]).isEqualTo(-1.0f);   // dkim fail
        assertThat(v[23]).isEqualTo(-0.5f);   // dmarc softfail
    }

    @Test
    void encodes_an_absent_timestamp_hour_and_day_as_negative_one() {
        FeatureSet noDate = new FeatureSet(
                new HeaderFeatures(true, 10, 0.0, 0, true, 1, false),
                new LinkFeatures(0, 0, false, false, 0),
                new TextFeatures(100, 20, 0.0, 0, 5.0),
                new TimingFeatures(false, null, null, false),
                new AuthFeatures("unknown", "unknown", "unknown"),
                null);

        float[] v = ModelFeatureVector.toVector(noDate);
        assertThat(v[17]).isEqualTo(0.0f);   // hasDate false
        assertThat(v[18]).isEqualTo(-1.0f);  // hour sentinel
        assertThat(v[19]).isEqualTo(-1.0f);  // day sentinel
    }

    @Nested
    class AuthEncoding {

        @Test
        void encodes_each_known_token_ordinally() {
            assertThat(ModelFeatureVector.authScore("pass")).isEqualTo(1.0f);
            assertThat(ModelFeatureVector.authScore("softfail")).isEqualTo(-0.5f);
            assertThat(ModelFeatureVector.authScore("fail")).isEqualTo(-1.0f);
        }

        @Test
        void treats_neutral_none_and_unknown_tokens_as_zero() {
            assertThat(ModelFeatureVector.authScore("neutral")).isEqualTo(0.0f);
            assertThat(ModelFeatureVector.authScore("none")).isEqualTo(0.0f);
            assertThat(ModelFeatureVector.authScore("temperror")).isEqualTo(0.0f);
            assertThat(ModelFeatureVector.authScore("unknown")).isEqualTo(0.0f);
            assertThat(ModelFeatureVector.authScore("something-new")).isEqualTo(0.0f);
        }

        @Test
        void treats_a_null_token_as_zero() {
            assertThat(ModelFeatureVector.authScore(null)).isEqualTo(0.0f);
        }
    }

    @Test
    void excludes_the_embedding_from_the_vector() {
        FeatureSet withEmbedding = new FeatureSet(
                fullFeatures().header(),
                fullFeatures().link(),
                fullFeatures().text(),
                fullFeatures().timing(),
                fullFeatures().auth(),
                List.of(0.1f, 0.2f, 0.3f));

        // An embedding present or absent must not change the vector — it is not a
        // model input in this story.
        assertThat(ModelFeatureVector.toVector(withEmbedding))
                .hasSize(ModelFeatureVector.FEATURE_COUNT)
                .containsExactly(ModelFeatureVector.toVector(fullFeatures()));
    }

    @Test
    void rejects_a_null_feature_set() {
        assertThatThrownBy(() -> ModelFeatureVector.toVector(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
