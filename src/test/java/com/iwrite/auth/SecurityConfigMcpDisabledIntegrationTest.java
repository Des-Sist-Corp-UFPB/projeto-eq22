package com.iwrite.auth;

import com.iwrite.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The complementary case to {@link SecurityConfigMcpIntegrationTest}: MCP disabled — the default —
 * grants the transport paths no exemption at all. They are just like any other unmapped, protected
 * path: 401, never {@code permitAll}.
 */
@AutoConfigureMockMvc
class SecurityConfigMcpDisabledIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void transportPathsGetNoExemptionWhenMcpIsDisabled() throws Exception {
        // GET has nothing to say about CSRF, so the request reaches the authentication check: 401.
        mockMvc.perform(get("/sse")).andExpect(status().isUnauthorized());
        // POST is a state-changing method: with no exemption for this path, the CSRF filter — which
        // runs ahead of authentication — refuses it first: 403. Different filter, same conclusion as
        // the GET case: nothing here is permitAll while MCP is disabled.
        mockMvc.perform(post("/mcp/message")).andExpect(status().isForbidden());
    }
}
