package com.iwrite.mcp;

import com.iwrite.user.context.CurrentUserProvider;
import com.iwrite.user.context.DevelopmentCurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O transporte MCP não tem autenticação própria, então a única configuração suportada é a
 * identidade fixa de desenvolvimento limitada a loopback. Qualquer desvio disso — identidade
 * autenticada em vez da de desenvolvimento, ou identidade de desenvolvimento sem loopback — recusa
 * a inicialização do contexto antes que o servidor anuncie um transporte inutilizável.
 */
class McpLoopbackGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(McpLoopbackGuard.class);

    private ApplicationContextRunner withFixedDevelopmentIdentity() {
        return runner.withBean(CurrentUserProvider.class, () -> new DevelopmentCurrentUserProvider(
                UUID.randomUUID(), UUID.randomUUID(), ZoneId.of("UTC")));
    }

    @Test
    void recusaStartupComIdentidadeFixaSemEnderecoDeLoopback() {
        withFixedDevelopmentIdentity()
                .withPropertyValues("spring.ai.mcp.server.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("loopback");
                });
    }

    @Test
    void recusaStartupComIdentidadeFixaEmEnderecoRemoto() {
        withFixedDevelopmentIdentity()
                .withPropertyValues("spring.ai.mcp.server.enabled=true", "server.address=0.0.0.0")
                .run(context -> assertThat(context).hasFailed());

        withFixedDevelopmentIdentity()
                .withPropertyValues("spring.ai.mcp.server.enabled=true", "server.address=192.168.0.10")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void permiteStartupLocalLimitadoALoopback() {
        withFixedDevelopmentIdentity()
                .withPropertyValues("spring.ai.mcp.server.enabled=true", "server.address=127.0.0.1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(McpLoopbackGuard.class);
                });

        withFixedDevelopmentIdentity()
                .withPropertyValues("spring.ai.mcp.server.enabled=true", "server.address=::1")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void mcpDesabilitadoNaoRegistraOGuardNemFalha() {
        withFixedDevelopmentIdentity()
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(McpLoopbackGuard.class);
                });
    }

    @Test
    void recusaStartupComIdentidadeAutenticadaEmVezDaDeDesenvolvimento() {
        // Sem isto, o servidor subiria descobrível mas toda tool/resource falharia: nenhuma
        // requisição MCP popula um IWriteUserDetails real para AuthenticatedCurrentUserProvider.
        runner.withBean(CurrentUserProvider.class, NonDevelopmentProvider::new)
                .withPropertyValues("spring.ai.mcp.server.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("identidade fixa de desenvolvimento");
                });
    }

    @Test
    void recusaStartupComIdentidadeAutenticadaMesmoEmEnderecoDeLoopback() {
        // O loopback sozinho não basta: sem a identidade fixa de desenvolvimento não há quem
        // autentique as invocações, então o guard recusa antes de sequer avaliar o endereço.
        runner.withBean(CurrentUserProvider.class, NonDevelopmentProvider::new)
                .withPropertyValues("spring.ai.mcp.server.enabled=true", "server.address=127.0.0.1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void mcpDesabilitadoComIdentidadeAutenticadaSobeNormalmente() {
        runner.withBean(CurrentUserProvider.class, NonDevelopmentProvider::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(McpLoopbackGuard.class);
                });
    }

    @Test
    void enderecoInvalidoNaoContaComoLoopback() {
        assertThat(McpLoopbackGuard.isLoopback("nao-resolve-!!")).isFalse();
        assertThat(McpLoopbackGuard.isLoopback("")).isFalse();
        assertThat(McpLoopbackGuard.isLoopback(null)).isFalse();
        assertThat(McpLoopbackGuard.isLoopback("127.0.0.1")).isTrue();
        assertThat(McpLoopbackGuard.isLoopback("localhost")).isTrue();
    }

    private static final class NonDevelopmentProvider implements CurrentUserProvider {

        @Override
        public UUID userId() {
            return UUID.randomUUID();
        }

        @Override
        public UUID tenantId() {
            return UUID.randomUUID();
        }

        @Override
        public ZoneId effectiveZoneId() {
            return ZoneId.of("UTC");
        }
    }
}
