package com.antispam;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the two observable HTTP contracts of the walking skeleton: a liveness
 * probe at /health and a build-identity report at /info. Both are served by
 * Spring Boot Actuator remapped to the application root (see application.yml).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthAndInfoEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void health_returns_200_and_status_up() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void info_reports_build_version_so_deployed_builds_are_identifiable() throws Exception {
        mockMvc.perform(get("/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.build.version").exists())
                .andExpect(jsonPath("$.build.commit").exists());
    }
}
