package com.iwrite.mcp;

import com.iwrite.user.context.CurrentUserProvider;
import com.iwrite.user.context.DevelopmentCurrentUserProvider;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * The MCP transport ({@code /sse}, {@code /mcp/message}) has no authentication of its own: no MCP
 * client performs {@code POST /api/auth/login}, so {@link com.iwrite.auth.SecurityConfig} can only
 * ever admit it as {@code permitAll}. The one configuration where that is safe is the development identity
 * ({@link DevelopmentCurrentUserProvider}, a single fixed principal for every caller) confined to a
 * loopback-only process — nothing off-box can reach it, and there is no per-caller identity to get
 * wrong. This guard enforces both halves of that contract before the server ever advertises the
 * transport as discoverable:
 *
 * <ul>
 *   <li>the active {@link CurrentUserProvider} must be {@link DevelopmentCurrentUserProvider} — an
 *       {@link com.iwrite.user.context.AuthenticatedCurrentUserProvider} deployment would publish a
 *       transport every one of whose operations then throws {@code SessionAuthenticationException},
 *       since nothing ever populates a real {@code IWriteUserDetails} principal for it; and</li>
 *   <li>{@code server.address} must resolve to a loopback address — the fixed development identity
 *       must never be reachable off-box.</li>
 * </ul>
 *
 * Authenticating individual MCP clients is future work, not something this guard attempts.
 */
@Component
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true")
public class McpLoopbackGuard implements InitializingBean {

    private final CurrentUserProvider currentUserProvider;
    private final String serverAddress;

    public McpLoopbackGuard(
            CurrentUserProvider currentUserProvider,
            @Value("${server.address:}") String serverAddress
    ) {
        this.currentUserProvider = currentUserProvider;
        this.serverAddress = serverAddress;
    }

    @Override
    public void afterPropertiesSet() {
        if (!(currentUserProvider instanceof DevelopmentCurrentUserProvider)) {
            throw new IllegalStateException(
                    "Servidor MCP habilitado (spring.ai.mcp.server.enabled=true), mas o CurrentUserProvider "
                            + "ativo não é a identidade fixa de desenvolvimento. O transporte MCP não tem "
                            + "autenticação própria: nenhum cliente MCP realiza login, então toda operação "
                            + "falharia sem uma identidade resolvida. Habilite a identidade de desenvolvimento "
                            + "(iwrite.current-user.development.enabled=true) para usar o MCP, ou desabilite-o "
                            + "(IWRITE_MCP_ENABLED=false)."
            );
        }
        if (!isLoopback(serverAddress)) {
            throw new IllegalStateException(
                    "Servidor MCP habilitado com identidade fixa de desenvolvimento, mas o servidor não está "
                            + "limitado a loopback. Defina server.address=127.0.0.1 (SERVER_ADDRESS=127.0.0.1) "
                            + "para uso local, ou desabilite o MCP (IWRITE_MCP_ENABLED=false)."
            );
        }
    }

    static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(address.trim()).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }
}
