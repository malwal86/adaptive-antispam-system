package com.antispam.features.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.features.EmailFeatures;
import com.antispam.features.EmailFeatureExtractor;
import com.antispam.features.EmailFeaturesService;
import com.antispam.features.FeatureSet;
import com.antispam.features.FeatureSet.AuthFeatures;
import com.antispam.features.FeatureSet.HeaderFeatures;
import com.antispam.features.FeatureSet.LinkFeatures;
import com.antispam.features.FeatureSet.TextFeatures;
import com.antispam.features.FeatureSet.TimingFeatures;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Web-contract test for the features endpoint, standalone (no Spring context, no
 * database) so it runs everywhere. It pins the two outcomes that matter to a
 * client: a present feature set serializes to the documented JSON shape, and an
 * email without features returns 404.
 */
class EmailFeaturesControllerTest {

    private EmailFeaturesService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(EmailFeaturesService.class);
        // Register JSR-310 so the Instant extractedAt serializes, matching the
        // Boot-configured mapper the real app uses.
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders.standaloneSetup(new EmailFeaturesController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void returns_the_current_feature_set_as_json() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.findCurrent(any())).thenReturn(Optional.of(sampleFeatures(id)));

        mockMvc.perform(get("/emails/{id}/features", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailId").value(id.toString()))
                .andExpect(jsonPath("$.featureVersion").value(EmailFeatureExtractor.FEATURE_VERSION))
                .andExpect(jsonPath("$.features.auth.spf").value("pass"))
                .andExpect(jsonPath("$.features.link.urlCount").value(1))
                .andExpect(jsonPath("$.features.timing.hourOfDayUtc").value(14))
                .andExpect(jsonPath("$.features.embedding").value(Matchers.nullValue()));
    }

    @Test
    void returns_404_when_no_features_have_been_extracted() throws Exception {
        when(service.findCurrent(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/emails/{id}/features", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    private static EmailFeatures sampleFeatures(UUID id) {
        FeatureSet fs = new FeatureSet(
                new HeaderFeatures(true, 5, 0.0, 0, true, 1, false),
                new LinkFeatures(1, 1, false, false, 19),
                new TextFeatures(40, 6, 0.05, 1, 4.0),
                new TimingFeatures(true, 14, 3, false),
                new AuthFeatures("pass", "pass", "pass"),
                null);
        return new EmailFeatures(id, EmailFeatureExtractor.FEATURE_VERSION, fs, Instant.parse("2024-03-13T14:30:00Z"));
    }
}
