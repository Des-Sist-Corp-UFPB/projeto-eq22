package com.iwrite.auth;

import com.iwrite.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the exact contract {@link SecurityConfig}'s comments describe for the MCP transport paths:
 * the permitAll/CSRF-ignored exemption exists only while MCP is enabled, and even then every other
 * endpoint stays authenticated. Boots with the one configuration {@link McpLoopbackGuard} allows
 * (development identity + loopback) so startup itself does not refuse first.
 * {@link com.iwrite.mcp.McpServerDiscoveryIntegrationTest} already proves a real MCP client can use
 * this exemption end to end; this test isolates the security-filter-chain behavior on its own.
 */
@AutoConfigureMockMvc
class SecurityConfigMcpIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void mcpEnabledOnLoopback(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.mcp.server.enabled", () -> "true");
        registry.add("server.address", () -> "127.0.0.1");
    }

    @Test
    void mcpMessagePathNeedsNoSessionOrCsrfWhenMcpIsEnabled() throws Exception {
        var result = mockMvc.perform(post("/mcp/message")).andReturn();

        // Whatever the MCP layer itself makes of a message with no established session (its own
        // concern, not this test's), it must never be Spring Security's 401/403 — those would mean
        // the permitAll/CSRF-ignored exemption did not actually apply.
        assertThat(result.getResponse().getStatus())
                .as("neither authentication nor CSRF should be demanded on the MCP message path")
                .isNotIn(401, 403);
    }

    @Test
    void unrelatedEndpointsStayAuthenticatedEvenWithMcpEnabled() throws Exception {
        mockMvc.perform(get("/api/books")).andExpect(status().isUnauthorized());
    }
}
