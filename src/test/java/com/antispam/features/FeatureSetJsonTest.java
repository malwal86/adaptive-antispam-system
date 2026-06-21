package com.antispam.features;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.features.FeatureSet.AuthFeatures;
import com.antispam.features.FeatureSet.HeaderFeatures;
import com.antispam.features.FeatureSet.LinkFeatures;
import com.antispam.features.FeatureSet.TextFeatures;
import com.antispam.features.FeatureSet.TimingFeatures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * The JSONB payload is the storage contract for {@code email_features}. This pins
 * down that a {@link FeatureSet} round-trips through Jackson unchanged — the same
 * mapper the repository uses — so a serialization regression is caught without a
 * database. It also confirms the null embedding hook survives the round trip.
 */
class FeatureSetJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void feature_set_round_trips_through_jackson_unchanged() throws Exception {
        FeatureSet original = new FeatureSet(
                new HeaderFeatures(true, 12, 1.0, 2, true, 2, false),
                new LinkFeatures(3, 2, true, false, 19),
                new TextFeatures(120, 20, 0.333333, 5, 4.5),
                new TimingFeatures(true, 14, 3, false),
                new AuthFeatures("pass", "pass", "fail"),
                null);

        String json = mapper.writeValueAsString(original);
        FeatureSet restored = mapper.readValue(json, FeatureSet.class);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.embedding()).isNull();
    }
}
