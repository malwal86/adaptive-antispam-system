package com.antispam.feedback;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.AbstractPostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The persona endpoints (story 07.01): {@code GET /personas} lists the seeded catalogue and
 * {@code POST /personas/population} returns the realized per-persona counts for a spec. The test
 * seeds its own uniquely-named personas (the suite shares the DB) and scopes assertions to them.
 */
@AutoConfigureMockMvc
class PersonasApiTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PersonaRepository repository;

    @Test
    void lists_a_seeded_persona_with_its_biases_and_malicious_flag() throws Exception {
        String name = "api-" + UUID.randomUUID();
        repository.seed(List.of(new PersonaDefinition(name, 0.8, 0.1, 0.9, true).toPersona()));

        mockMvc.perform(get("/personas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '" + name + "')].clickBias").value(Matchers.contains(0.8)))
                .andExpect(jsonPath("$[?(@.name == '" + name + "')].malicious").value(Matchers.contains(true)));
    }

    @Test
    void assembles_a_population_and_reports_the_mix() throws Exception {
        String a = "mix-a-" + UUID.randomUUID();
        String b = "mix-b-" + UUID.randomUUID();
        repository.seed(List.of(
                new PersonaDefinition(a, 0.5, 0.5, 0.5, false).toPersona(),
                new PersonaDefinition(b, 0.5, 0.5, 0.5, true).toPersona()));

        String body = """
                {"seed": 5, "size": 100, "weights": {"%s": 4, "%s": 1}}
                """.formatted(a, b);

        mockMvc.perform(post("/personas/population").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.counts['" + a + "']").value(80))
                .andExpect(jsonPath("$.counts['" + b + "']").value(20));
    }

    @Test
    void rejects_a_population_referencing_an_unknown_persona() throws Exception {
        String body = """
                {"seed": 1, "size": 10, "weights": {"ghost-%s": 1}}
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/personas/population").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(Matchers.containsString("unknown persona")));
    }
}
