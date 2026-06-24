package com.antispam.arena.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.arena.AdversarialEmail;
import com.antispam.arena.AdversarialEmailRepository;
import com.antispam.arena.AttackerUnavailableException;
import com.antispam.arena.MutationException;
import com.antispam.arena.MutationService;
import com.antispam.arena.MutationStrategy;
import com.antispam.seed.GroundTruthLabel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Web-contract test for the arena mutation endpoints, standalone (no Spring context, no provider, no
 * DB). It pins the client-facing outcomes: minting a variant returns 201 with its lineage, a bad
 * seed is a 400 (not a 500), an unreachable attacker is a 503, and the by-seed listing serializes an
 * attack family.
 */
class MutationControllerTest {

    private MutationService mutationService;
    private AdversarialEmailRepository variants;
    private MockMvc mockMvc;

    private static final UUID SEED_ID = UUID.randomUUID();
    private static final UUID VARIANT_EMAIL_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mutationService = Mockito.mock(MutationService.class);
        variants = Mockito.mock(AdversarialEmailRepository.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders.standaloneSetup(new MutationController(mutationService, variants))
                .setControllerAdvice(new ArenaExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void minting_a_variant_returns_201_with_its_lineage() throws Exception {
        UUID id = UUID.randomUUID();
        when(mutationService.mutate(SEED_ID, MutationStrategy.HOMOGLYPH)).thenReturn(
                new AdversarialEmail(id, VARIANT_EMAIL_ID, SEED_ID, null,
                        MutationStrategy.HOMOGLYPH, GroundTruthLabel.PHISH, "gpt-4o-mini", null, null, Instant.EPOCH));

        mockMvc.perform(post("/arena/mutations")
                        .contentType("application/json")
                        .content("{\"seedEmailId\":\"" + SEED_ID + "\",\"strategy\":\"HOMOGLYPH\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.variantEmailId").value(VARIANT_EMAIL_ID.toString()))
                .andExpect(jsonPath("$.seedEmailId").value(SEED_ID.toString()))
                .andExpect(jsonPath("$.parentVariantId").doesNotExist())
                .andExpect(jsonPath("$.strategy").value("homoglyph"))
                .andExpect(jsonPath("$.groundTruthLabel").value("phish"))
                .andExpect(jsonPath("$.attackerModel").value("gpt-4o-mini"));
    }

    @Test
    void rejects_a_bad_seed_with_400() throws Exception {
        when(mutationService.mutate(any(), any()))
                .thenThrow(new MutationException("seed email not found: " + SEED_ID));

        mockMvc.perform(post("/arena/mutations")
                        .contentType("application/json")
                        .content("{\"seedEmailId\":\"" + SEED_ID + "\",\"strategy\":\"SYNONYM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("seed email not found: " + SEED_ID));
    }

    @Test
    void surfaces_an_unreachable_attacker_as_503() throws Exception {
        when(mutationService.mutate(any(), any()))
                .thenThrow(new AttackerUnavailableException("arena attacker disabled"));

        mockMvc.perform(post("/arena/mutations")
                        .contentType("application/json")
                        .content("{\"seedEmailId\":\"" + SEED_ID + "\",\"strategy\":\"SYNONYM\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("arena attacker disabled"));
    }

    @Test
    void lists_a_seeds_attack_family_as_json() throws Exception {
        when(variants.findBySeed(SEED_ID)).thenReturn(List.of(
                new AdversarialEmail(UUID.randomUUID(), VARIANT_EMAIL_ID, SEED_ID, null,
                        MutationStrategy.REFRAME, GroundTruthLabel.SPAM, "gpt-4o-mini", null, null, Instant.EPOCH)));

        mockMvc.perform(get("/arena/seeds/{seedId}/mutations", SEED_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seedEmailId").value(SEED_ID.toString()))
                .andExpect(jsonPath("$[0].strategy").value("reframe"))
                .andExpect(jsonPath("$[0].groundTruthLabel").value("spam"));
    }
}
