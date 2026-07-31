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
 * A aplicação ainda não tem autenticação real: com o
 * {@link DevelopmentCurrentUserProvider}, todo cliente MCP herda a mesma
 * identidade fixa. Este guard recusa a inicialização do servidor MCP quando o
 * processo não está limitado a loopback ({@code server.address} ausente ou não
 * loopback), impedindo exposição remota anônima com identidade fixa.
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
            return;
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
