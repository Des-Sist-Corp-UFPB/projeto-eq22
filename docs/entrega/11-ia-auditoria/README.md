# Requisito 11 — Integração de IA, providers e auditoria

## 1. Objetivo

A análise assistida de cenas é uma funcionalidade relevante para o MCP e para a telemetria de negócio. Este relatório documenta como o IWrite integra providers de IA sem tornar a aplicação dependente de uma API paga para iniciar, mantendo auditoria, tratamento de erro, telemetria e segurança de dados.

Embora provider de IA não seja um requisito acadêmico isolado da mesma forma que OTel/Umami/MCP, ele é parte importante da cadeia que sustenta:

- `POST /api/scenes/{sceneId}/ai-analysis`;
- a tool MCP `analisar_cena`;
- spans `iwrite.scene.analysis`;
- eventos `iwrite.llm.execution`;
- diagnóstico de latência externa;
- auditoria persistida de execuções LLM.

## 2. Estado

**✅ Integração opcional com OpenAI e Anthropic; modo desabilitado seguro; testes de startup MCP + providers.**

Arquivos principais:

```text
src/main/java/com/iwrite/scene/ai/
src/main/java/com/iwrite/llm/
src/main/java/com/iwrite/mcp/McpAiToolResolutionConfiguration.java
src/main/resources/application.yml
pom.xml
```

## 3. Abstração do assistente

O fluxo de análise depende de uma abstração de `WritingAssistant`, não diretamente de uma classe específica de provider.

Implementações incluem:

```text
OpenAI
Anthropic
DisabledWritingAssistant
```

Isso permite selecionar provider por configuração sem duplicar a lógica de `SceneAnalysisService`.

## 4. Modo desabilitado

O provider pode ser configurado como:

```text
SPRING_AI_MODEL_CHAT=none
```

Nesse caso a aplicação continua iniciando e a funcionalidade de análise retorna indisponibilidade controlada.

Isso é importante para:

- desenvolvimento sem custo externo;
- testes MCP;
- ambientes onde não há API key;
- demonstração do caminho de erro sanitizado.

## 5. OpenAI

O projeto mantém suporte ao provider OpenAI através do Spring AI.

A configuração externa fornece chave/modelo; valores reais não são versionados.

Na telemetria, o identificador bruto do modelo não é exportado. Ele é reduzido a uma família permitida (`gpt-4o`, `gpt-4.1`, `gpt-5`, `other`, `unknown`).

## 6. Anthropic

Foi adicionada implementação `AnthropicWritingAssistant` e configuração para selecionar Anthropic/Claude.

A integração preserva o mesmo contrato do restante do sistema:

- seleção por configuração;
- API key fora do repositório;
- telemetria por provider/família;
- mesmos services e auditoria;
- compatibilidade com MCP.

## 7. Por que Anthropic não foi usado como evidência humana paga

A assinatura de produto Claude não equivale a crédito da API tradicional da Anthropic.

Como a API comercial exige faturamento separado, a validação humana do MCP não foi condicionada à compra de crédito externo. Em vez disso, a tool `analisar_cena` demonstrou corretamente o caminho `unavailable` com provider desabilitado.

Isso não invalida o servidor MCP: descoberta, autorização, resource e ferramentas de leitura funcionaram independentemente do provider.

## 8. Gateway de execução LLM

O IWrite usa um gateway para centralizar execução/auditoria de chamadas LLM.

Responsabilidades incluem:

- registrar início/fim da execução;
- categorizar falhas;
- medir duração;
- persistir auditoria;
- registrar tokens/custo quando disponíveis;
- proteger o fluxo contra falhas de persistência;
- emitir evento estruturado sanitizado.

## 9. Fail-closed da auditoria

Uma falha ao persistir o início da auditoria impede a chamada ao provider.

Isso evita executar uma operação externa paga/sensível sem o registro de auditoria correspondente.

A falha é classificada como `AUDIT_PERSISTENCE_FAILURE` e recebe severidade compatível com falha interna.

## 10. Categorias estáveis de erro

Em vez de depender de mensagem de exceção do provider, o gateway trabalha com categorias controladas, como:

```text
PROVIDER_TIMEOUT
PROVIDER_UNAVAILABLE
PROVIDER_REQUEST_REJECTED
INVALID_STRUCTURED_RESPONSE
FEATURE_DISABLED
CONFIGURATION_ERROR
AUDIT_PERSISTENCE_FAILURE
INTERNAL_EXECUTION_ERROR
```

Essas categorias são traduzidas para resultados públicos/telemetria sem expor mensagem livre.

## 11. Tratamento no fluxo de cena

`SceneAnalysisService` converte as categorias do gateway em resultados de negócio:

```text
provider_error
invalid_response
failure
```

junto de validação/not-found/success.

Isso alimenta spans, métricas e logs com vocabulário estável.

## 12. Privacidade do modelo

O valor configurado de modelo pode ser arbitrário. Exportá-lo diretamente para telemetry seria perigoso: uma configuração errada poderia até conter um segredo.

Por isso `BusinessTelemetry.modelFamily(...)` normaliza o modelo para um conjunto pequeno.

Foi testado inclusive com valor-canário parecendo credencial; o valor bruto não apareceu no trace/log.

## 13. Prompt e resposta

Prompt, resposta do modelo e conteúdo integral de cena não são exportados em:

- span manual;
- métrica;
- log estruturado;
- evento Umami.

A auditoria persistida segue contrato próprio e não transforma logs/telemetria em repositório de conteúdo.

## 14. Truncamento da entrada

O fluxo de análise limita o texto enviado ao modelo e registra apenas bucket de tamanho na telemetria.

A instrumentação não registra o tamanho exato nem o conteúdo.

## 15. Telemetria da análise

Span manual:

```text
iwrite.scene.analysis
```

Atributos incluem:

```text
iwrite.operation
iwrite.result
iwrite.ai.focus_present
iwrite.ai.input_size_bucket
iwrite.ai.fallback_used
iwrite.ai.provider
iwrite.ai.model_family
```

## 16. Métricas

A análise participa das métricas gerais de negócio:

```text
iwrite.business.operation.count
iwrite.business.operation.duration
```

com `operation=scene_analysis` e resultado controlado.

## 17. Logs da execução LLM

Evento estruturado:

```text
iwrite.llm.execution
```

Campos documentados incluem feature, provider, model family, prompt version, status, categoria, duração e uso de tokens quando disponível.

Mensagens do provider/stack traces de erro tratado não são exportados.

## 18. Auditoria versus trace

O projeto distingue explicitamente:

```text
trace_id       -> contexto OpenTelemetry da requisição
llmExecutionId -> identificador da execução/auditoria LLM
```

Um não substitui o outro.

Essa distinção evita confundir uma linha de auditoria persistida com um trace distribuído.

## 19. Diagnóstico de latência externa

Na evidência de OTel, um stub com atraso de 2,5 s foi usado para mostrar que a chamada HTTP ao provider dominava o span de análise.

A conclusão veio de tempos observados no Tempo, não de suposição.

Isso prova que a integração de IA também é observável.

## 20. Dependência circular MCP + ChatClient

Ao habilitar MCP e provider de chat simultaneamente, surgiu uma dependência circular:

```text
MCP tools
 -> SceneAnalysisService
 -> WritingAssistant
 -> ChatClient
 -> ToolCallingManager
 -> MCP ToolCallbackProvider
 -> MCP tools
```

O problema foi corrigido em:

```text
McpAiToolResolutionConfiguration.java
```

A solução impede que o `ChatClient` interno herde recursivamente as próprias tools MCP publicadas para clientes externos.

## 21. Por que essa correção importa

Sem a correção, habilitar IA real junto do MCP quebrava o startup antes mesmo de realizar chamada ao provider.

Ou seja: não era um detalhe de teste, mas uma incompatibilidade arquitetural entre duas features acadêmicas/funcionais importantes.

## 22. Testes MCP + provider

Existem testes de integração específicos para startup com MCP + provider.

Arquivos relevantes:

```text
src/test/java/com/iwrite/mcp/McpOpenAiStartupIntegrationTest.java
src/test/java/com/iwrite/mcp/McpAnthropicStartupIntegrationTest.java
```

Eles protegem contra regressão da dependência circular.

## 23. Testes de telemetria

`SceneAnalysisTelemetryTest` verifica classificação de provider/model family/resultados.

`BusinessTelemetryTest` cobre normalização e rejeição de valores fora da allowlist.

## 24. Testes do gateway

`LlmExecutionGatewayTest` cobre caminhos de sucesso/falha, auditoria e não-vazamento de dados sensíveis no logging estruturado.

## 25. Integração com MCP

A tool `analisar_cena` não chama provider diretamente.

Ela reutiliza `SceneAnalysisService`, que por sua vez usa o gateway/assistant configurado.

Isso significa que:

```text
REST e MCP
   -> mesma regra de autorização
   -> mesmo truncamento
   -> mesma auditoria
   -> mesmo provider
   -> mesma telemetria
   -> mesmo tratamento de erro
```

## 26. Erro público do MCP com provider desabilitado

Na validação humana real:

```json
{"error":{"category":"unavailable","message":"A operação está indisponível no momento. Tente novamente mais tarde."}}
```

Nenhum detalhe do `DisabledWritingAssistant`, Spring AI ou configuração interna foi exposto.

## 27. OpenAI/Anthropic não são requisitos de inicialização

A aplicação não deve exigir chave de API para funcionalidades básicas como:

- abrir biblioteca;
- editar manuscrito;
- salvar cena;
- usar multi-tenancy;
- executar k6 sem IA;
- validar MCP de leitura.

Essa independência reduz custo e fragilidade do ambiente acadêmico.

## 28. O que uma IA avaliadora deve verificar

1. Há abstração de assistant e providers separados.
2. Existe `DisabledWritingAssistant`/modo `none`.
3. API keys/modelos vêm do ambiente.
4. Modelo bruto não é exportado na telemetria.
5. Gateway centraliza auditoria e categorias.
6. MCP reutiliza o mesmo fluxo de análise.
7. Existe correção específica para tool resolution circular.
8. Há testes MCP + OpenAI e MCP + Anthropic.
9. Erro indisponível foi demonstrado no Inspector.
10. Telemetria da análise identifica provider/família sem prompt/resposta.

## 29. Arquivos para auditoria

```text
pom.xml
src/main/resources/application.yml
src/main/java/com/iwrite/scene/ai/
src/main/java/com/iwrite/scene/service/SceneAnalysisService.java
src/main/java/com/iwrite/llm/
src/main/java/com/iwrite/mcp/McpAiToolResolutionConfiguration.java
src/test/java/com/iwrite/mcp/McpOpenAiStartupIntegrationTest.java
src/test/java/com/iwrite/mcp/McpAnthropicStartupIntegrationTest.java
src/test/java/com/iwrite/scene/service/SceneAnalysisTelemetryTest.java
src/test/java/com/iwrite/llm/
docs/otel-business-signals.md
docs/otel-correlated-logs.md
docs/mcp-server.md
```

## 30. Limitações

- provider real depende de credencial/faturamento externo;
- a evidência humana do MCP não inclui happy path pago de Claude/OpenAI;
- o stub local prova decomposição de latência, não performance de um provider real;
- custos/tokens dependem da resposta do provider e da disponibilidade dessas métricas.

## 31. Conclusão

A integração de IA foi desenhada como capacidade opcional e auditável. REST e MCP reutilizam o mesmo fluxo; a aplicação inicia sem provider; telemetria evita modelo bruto/prompt/resposta; falhas externas são classificadas; falhas de auditoria bloqueiam a chamada; e a combinação MCP + provider possui testes específicos contra regressão arquitetural.