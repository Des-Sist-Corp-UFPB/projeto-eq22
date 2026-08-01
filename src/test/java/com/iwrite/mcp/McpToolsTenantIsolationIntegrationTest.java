package com.iwrite.mcp;

import com.iwrite.audit.entity.AuditAction;
import com.iwrite.audit.entity.AuditResult;
import com.iwrite.audit.repository.AuditLogRepository;
import com.iwrite.book.service.BookCollaboratorService;
import com.iwrite.mcp.IwriteMcpTools.McpBookSummary;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.SwitchableCurrentUserProvider;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.entity.TenantMembershipRole;
import com.iwrite.tenant.repository.TenantRepository;
import com.iwrite.user.entity.User;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_TENANT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@Import(McpToolsTenantIsolationIntegrationTest.CurrentUserTestConfiguration.class)
@TestPropertySource(properties = "spring.ai.mcp.server.enabled=true")
class McpToolsTenantIsolationIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private IwriteMcpTools mcpTools;

    @Autowired
    private McpServerFeatures.SyncResourceTemplateSpecification outlineResourceTemplate;

    @Autowired
    private SwitchableCurrentUserProvider currentUserProvider;

    @Autowired
    private BookCollaboratorService bookCollaboratorService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private UUID tenantBId;
    private UUID tenantBUserId;

    @BeforeEach
    void setUpTenants() {
        currentUserProvider.reset();
        Tenant tenantB = new Tenant();
        tenantB.setName("Tenant B MCP");
        tenantB.setDefaultTimeZoneId("UTC");
        tenantBId = tenantRepository.save(tenantB).getId();
        tenantBUserId = createMember(tenantBId, "mcp-tenant-b@iwrite.local");
    }

    @AfterEach
    void resetTenant() {
        currentUserProvider.reset();
    }

    @Test
    void listaApenasLivrosAcessiveisDoTenantAtual() {
        var world = createStoryWorld("MCP Livro A");

        switchToTenantB();
        createBook("MCP Livro B");

        List<McpBookSummary> tenantBBooks = mcpTools.listarLivrosAcessiveis();
        assertThat(tenantBBooks).extracting(McpBookSummary::title).contains("MCP Livro B");
        assertThat(tenantBBooks).extracting(McpBookSummary::id).doesNotContain(world.book().id());

        currentUserProvider.reset();
        assertThat(mcpTools.listarLivrosAcessiveis())
                .extracting(McpBookSummary::id)
                .contains(world.book().id());
    }

    @Test
    void membroDoMesmoTenantSemAcessoNaoEnumeraLivroDeOutroUsuario() {
        var world = createStoryWorld("MCP Livro privado");
        UUID sameTenantOutsiderId = createMember(DEFAULT_TENANT_ID, "mcp-same-tenant-outsider@iwrite.local");

        currentUserProvider.switchTo(sameTenantOutsiderId, DEFAULT_TENANT_ID, ZoneId.of("UTC"));

        assertThat(mcpTools.listarLivrosAcessiveis())
                .extracting(McpBookSummary::id)
                .doesNotContain(world.book().id());
        assertNotFound(() -> mcpTools.obterOutlineLivro(world.book().id().toString()));
    }

    @Test
    void crossTenantEInexistenteTemMesmaSemanticaNaoEnumeravel() {
        var world = createStoryWorld("MCP Livro isolado");

        switchToTenantB();
        McpToolException crossTenant = catchThrowableOfType(
                McpToolException.class,
                () -> mcpTools.obterOutlineLivro(world.book().id().toString())
        );
        McpToolException nonexistent = catchThrowableOfType(
                McpToolException.class,
                () -> mcpTools.obterOutlineLivro(UUID.randomUUID().toString())
        );

        assertThat(crossTenant.category()).isEqualTo(McpToolException.CATEGORY_NOT_FOUND);
        assertThat(nonexistent.category()).isEqualTo(McpToolException.CATEGORY_NOT_FOUND);
        assertThat(crossTenant.getMessage()).isEqualTo(nonexistent.getMessage());

        assertNotFound(() -> mcpTools.analisarCena(world.scene().id().toString(), null));
    }

    @Test
    void colaboradorAcessaEDepoisDaRevogacaoPerdeAcesso() {
        var world = createStoryWorld("MCP Livro colaborativo");
        UUID collaboratorId = createMember(DEFAULT_TENANT_ID, "mcp-collaborator@iwrite.local");
        bookCollaboratorService.add(world.book().id(), collaboratorId);

        currentUserProvider.switchTo(collaboratorId, DEFAULT_TENANT_ID, ZoneId.of("UTC"));
        assertThat(mcpTools.obterOutlineLivro(world.book().id().toString()).title())
                .isEqualTo("MCP Livro colaborativo");
        assertThat(mcpTools.listarLivrosAcessiveis())
                .extracting(McpBookSummary::id)
                .contains(world.book().id());

        currentUserProvider.reset();
        bookCollaboratorService.remove(world.book().id(), collaboratorId);

        currentUserProvider.switchTo(collaboratorId, DEFAULT_TENANT_ID, ZoneId.of("UTC"));
        assertNotFound(() -> mcpTools.obterOutlineLivro(world.book().id().toString()));
    }

    @Test
    void parametrosInvalidosRetornamErroEstruturadoSemDetalhesInternos() {
        McpToolException invalidUuid = catchThrowableOfType(
                McpToolException.class,
                () -> mcpTools.obterOutlineLivro("nao-e-um-uuid")
        );
        assertThat(invalidUuid.category()).isEqualTo(McpToolException.CATEGORY_INVALID_REQUEST);
        assertSanitized(invalidUuid);

        var world = createStoryWorld("MCP validação");
        McpToolException focusTooLong = catchThrowableOfType(
                McpToolException.class,
                () -> mcpTools.analisarCena(world.scene().id().toString(), "x".repeat(301))
        );
        assertThat(focusTooLong.category()).isEqualTo(McpToolException.CATEGORY_INVALID_REQUEST);
    }

    @Test
    void analiseELimitadaPorIdentidadeSemVazarParaOutroUsuario() {
        var world = createStoryWorld("MCP limite de análise");
        UUID heavyUserId = createMember(DEFAULT_TENANT_ID, "mcp-heavy-user@iwrite.local");
        bookCollaboratorService.add(world.book().id(), heavyUserId);
        currentUserProvider.switchTo(heavyUserId, DEFAULT_TENANT_ID, ZoneId.of("UTC"));

        // IA desabilitada no contexto de teste: cada tentativa falha (sem custo), mas conta na janela.
        for (int attempt = 0; attempt < 3; attempt++) {
            McpToolException failure = catchThrowableOfType(
                    McpToolException.class,
                    () -> mcpTools.analisarCena(world.scene().id().toString(), null)
            );
            assertThat(failure.category()).isNotEqualTo(McpToolException.CATEGORY_RATE_LIMITED);
        }

        McpToolException limited = catchThrowableOfType(
                McpToolException.class,
                () -> mcpTools.analisarCena(world.scene().id().toString(), null)
        );
        assertThat(limited.category()).isEqualTo(McpToolException.CATEGORY_RATE_LIMITED);
        assertSanitized(limited);

        // O limite é por identidade: o dono do livro não herda o esgotamento do colaborador.
        currentUserProvider.reset();
        McpToolException ownerFailure = catchThrowableOfType(
                McpToolException.class,
                () -> mcpTools.analisarCena(world.scene().id().toString(), null)
        );
        assertThat(ownerFailure.category()).isNotEqualTo(McpToolException.CATEGORY_RATE_LIMITED);
    }

    @Test
    void invocacaoBemSucedidaGeraAuditoriaComMetadadosSemConteudo() {
        var world = createStoryWorld("MCP auditoria");

        mcpTools.obterOutlineLivro(world.book().id().toString());

        assertThat(auditLogRepository.findAll())
                .anySatisfy(log -> {
                    assertThat(log.getAction()).isEqualTo(AuditAction.MCP_BOOK_OUTLINE_VIEWED);
                    assertThat(log.getResult()).isEqualTo(AuditResult.SUCCEEDED);
                    assertThat(log.getResourceId()).isEqualTo(world.book().id());
                    assertThat(log.getTenantId()).isEqualTo(DEFAULT_TENANT_ID);
                });
    }

    @Test
    void resourceOutlineRespeitaAutorizacaoENaoRevelaOutroTenant() {
        var world = createStoryWorld("MCP resource outline");
        String uri = "iwrite://books/" + world.book().id() + "/outline";

        McpSchema.ReadResourceResult result = readResource(uri);
        assertThat(result.contents()).hasSize(1);
        String json = ((McpSchema.TextResourceContents) result.contents().get(0)).text();
        assertThat(json).contains("MCP resource outline");

        switchToTenantB();
        McpToolException crossTenant = catchThrowableOfType(McpToolException.class, () -> readResource(uri));
        McpToolException nonexistent = catchThrowableOfType(
                McpToolException.class,
                () -> readResource("iwrite://books/" + UUID.randomUUID() + "/outline")
        );
        assertThat(crossTenant.category()).isEqualTo(McpToolException.CATEGORY_NOT_FOUND);
        assertThat(crossTenant.getMessage()).isEqualTo(nonexistent.getMessage());

        McpToolException invalidUri = catchThrowableOfType(
                McpToolException.class,
                () -> readResource("iwrite://books/../segredo/outline")
        );
        assertThat(invalidUri.category()).isEqualTo(McpToolException.CATEGORY_INVALID_REQUEST);
    }

    private McpSchema.ReadResourceResult readResource(String uri) {
        return outlineResourceTemplate.readHandler().apply(null, new McpSchema.ReadResourceRequest(uri));
    }

    private void assertNotFound(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(McpToolException.class)
                .satisfies(exception -> {
                    McpToolException mcpException = (McpToolException) exception;
                    assertThat(mcpException.category()).isEqualTo(McpToolException.CATEGORY_NOT_FOUND);
                    assertSanitized(mcpException);
                });
    }

    private void assertSanitized(McpToolException exception) {
        assertThat(exception.getMessage())
                .startsWith("{\"error\":{\"category\":\"")
                .doesNotContain("Exception")
                .doesNotContain("com.iwrite")
                .doesNotContain("\tat ");
    }

    private void switchToTenantB() {
        currentUserProvider.switchTo(tenantBUserId, tenantBId, ZoneId.of("UTC"));
    }

    private UUID createMember(UUID tenantId, String email) {
        User user = new User();
        user.setDisplayName(email);
        user.setEmail(email);
        user.setTimeZoneId("UTC");
        entityManager.persist(user);

        TenantMembership membership = new TenantMembership();
        membership.setTenant(entityManager.getReference(Tenant.class, tenantId));
        membership.setUser(user);
        membership.setRole(TenantMembershipRole.OWNER);
        entityManager.persist(membership);
        entityManager.flush();
        return user.getId();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CurrentUserTestConfiguration {

        @Bean
        @Primary
        SwitchableCurrentUserProvider switchableCurrentUserProvider() {
            return new SwitchableCurrentUserProvider();
        }
    }
}
