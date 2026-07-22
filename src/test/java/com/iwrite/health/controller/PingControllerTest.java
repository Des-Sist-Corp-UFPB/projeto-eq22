package com.iwrite.health.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PingControllerTest {

    private final MockMvc mockMvc =
            MockMvcBuilders.standaloneSetup(new PingController()).build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void pingReturnsRequiredClassroomHealthContract() throws Exception {
        String responseBody = mockMvc.perform(get("/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.service").value("eq22"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode response = objectMapper.readTree(responseBody);

        assertDoesNotThrow(
                () -> Instant.parse(response.get("timestamp").asText())
        );
    }
}
