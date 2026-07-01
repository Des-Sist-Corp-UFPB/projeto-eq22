# Relatório de Avaliação — EQ22 (DSC)

| | |
|---|---|
| **Data** | 2026-06-25 |
| **Repositório** | https://github.com/des-sist-corp-ufpb/projeto-eq22 |
| **Aplicação** | https://eq22.dsc.rodrigor.com |
| **Período de atividade** | 2026-04-30 → 2026-06-25 |
| **Total de commits** (sem merges, branch main) | 215 |
| **Integrantes** | Carlos Henrique Germano Alves (@carloshgalves), @github-classroom[bot] |

---

## 1. Tecnologias

- Spring Boot 3.4.1
- Flyway (26 migrations)

---

## 2. Análise Funcional

### Endpoints REST (59 mapeados)

| Método | Path | Arquivo |
|--------|------|---------|
| `DELETE` | `/api/books/{bookId}/collaborators/{userId}` | `BookCollaboratorController.java` |
| `GET` | `/api/books/{bookId}/collaborators` | `BookCollaboratorController.java` |
| `POST` | `/api/books/{bookId}/collaborators` | `BookCollaboratorController.java` |
| `DELETE` | `/api/books/{bookId}` | `BookController.java` |
| `GET` | `/api/books` | `BookController.java` |
| `GET` | `/api/books/{bookId}` | `BookController.java` |
| `PATCH` | `/api/books/{bookId}` | `BookController.java` |
| `POST` | `/api/books` | `BookController.java` |
| `GET` | `/api/books/{bookId}/dashboard` | `BookDashboardController.java` |
| `GET` | `/api/books/{bookId}/dashboard/contributions` | `BookDashboardController.java` |
| `GET` | `/api/books/{bookId}/export/docx` | `BookExportController.java` |
| `GET` | `/api/books/{bookId}/export/markdown` | `BookExportController.java` |
| `GET` | `/api/books/{bookId}/exports/manuscript` | `BookExportController.java` |
| `GET` | `/api/books/{bookId}/exports/notebook` | `BookExportController.java` |
| `DELETE` | `/api/sections/{sectionId}` | `BookSectionController.java` |
| `PATCH` | `/api/books/{bookId}/sections/reorder` | `BookSectionController.java` |
| `PATCH` | `/api/sections/{sectionId}` | `BookSectionController.java` |
| `POST` | `/api/books/{bookId}/sections` | `BookSectionController.java` |
| `DELETE` | `/api/chapters/{chapterId}` | `ChapterController.java` |
| `PATCH` | `/api/chapters/{chapterId}` | `ChapterController.java` |
| `PATCH` | `/api/sections/{sectionId}/chapters/reorder` | `ChapterController.java` |
| `POST` | `/api/sections/{sectionId}/chapters` | `ChapterController.java` |
| `DELETE` | `/api/characters/{characterId}` | `CharacterController.java` |
| `GET` | `/api/books/{bookId}/characters` | `CharacterController.java` |
| `GET` | `/api/characters/{characterId}` | `CharacterController.java` |
| `PATCH` | `/api/characters/{characterId}` | `CharacterController.java` |
| `POST` | `/api/books/{bookId}/characters` | `CharacterController.java` |
| `DELETE` | `/api/items/{itemId}` | `ItemController.java` |
| `GET` | `/api/books/{bookId}/items` | `ItemController.java` |
| `GET` | `/api/items/{itemId}` | `ItemController.java` |
| `PATCH` | `/api/items/{itemId}` | `ItemController.java` |
| `POST` | `/api/books/{bookId}/items` | `ItemController.java` |
| `DELETE` | `/api/locations/{locationId}` | `LocationController.java` |
| `GET` | `/api/books/{bookId}/locations` | `LocationController.java` |
| `GET` | `/api/locations/{locationId}` | `LocationController.java` |
| `PATCH` | `/api/locations/{locationId}` | `LocationController.java` |
| `POST` | `/api/books/{bookId}/locations` | `LocationController.java` |
| `DELETE` | `/api/notebook/categories/{categoryId}` | `NotebookController.java` |
| `DELETE` | `/api/notebook/notes/{noteId}` | `NotebookController.java` |
| `GET` | `/api/books/{bookId}/notebook/categories` | `NotebookController.java` |
| `GET` | `/api/books/{bookId}/notebook/notes` | `NotebookController.java` |
| `GET` | `/api/notebook/notes/{noteId}` | `NotebookController.java` |
| `PATCH` | `/api/notebook/categories/{categoryId}` | `NotebookController.java` |
| `PATCH` | `/api/notebook/notes/{noteId}` | `NotebookController.java` |
| `POST` | `/api/books/{bookId}/notebook/categories` | `NotebookController.java` |
| `POST` | `/api/books/{bookId}/notebook/notes` | `NotebookController.java` |
| `GET` | `/api/books/{bookId}/outline` | `OutlineController.java` |
| `GET` | `/ping` | `PingController.java` |
| `DELETE` | `/api/scenes/{sceneId}` | `SceneController.java` |
| `GET` | `/api/scenes/{sceneId}` | `SceneController.java` |
| `PATCH` | `/api/chapters/{chapterId}/scenes/reorder` | `SceneController.java` |
| `PATCH` | `/api/scenes/{sceneId}` | `SceneController.java` |
| `PATCH` | `/api/scenes/{sceneId}/content` | `SceneController.java` |
| `PATCH` | `/api/scenes/{sceneId}/planning` | `SceneController.java` |
| `POST` | `/api/chapters/{chapterId}/scenes` | `SceneController.java` |
| `GET` | `/api/scenes/{sceneId}/versions` | `SceneVersionController.java` |
| `GET` | `/api/scenes/{sceneId}/versions/{versionId}` | `SceneVersionController.java` |
| `POST` | `/api/scenes/{sceneId}/versions/{versionId}/restore` | `SceneVersionController.java` |
| `GET` | `/api/dashboard/me` | `UserDashboardController.java` |

### Entidades / Tabelas (40 encontradas)

- `sections`
- `notebook_categories`
- `notebook_notes`
- `book_notebook_settings`
- `chapters`
- `scenes`
- `users`
- `book_daily_writing_progress`
- `book_writing_schedules`
- `BookWritingScheduleRepository`
- `book_word_count_events`
- `scene_versions`
- `tenants`
- `tenant_memberships`
- `locations`
- `characters`
- `items`
- `books`
- `book_collaborators`
- `books (via V1__create_books.sql)`
- `locations (via V6__create_locations.sql)`
- `scene_versions (via V17__create_scene_versions.sql)`
- `sections (via V2__create_sections.sql)`
- `items (via V7__create_items.sql)`
- `book_collaborators (via V25__add_book_ownership_and_collaborators.sql)`
- `tenants (via V20__add_personal_tenant_and_user_timezone_foundation.sql)`
- `users (via V20__add_personal_tenant_and_user_timezone_foundation.sql)`
- `tenant_memberships (via V20__add_personal_tenant_and_user_timezone_foundation.sql)`
- `book_writing_schedules (via V16__create_book_writing_schedules.sql)`
- `book_writing_schedule_days (via V16__create_book_writing_schedules.sql)`
- `characters (via V5__create_characters.sql)`
- `scenes (via V4__create_scenes.sql)`
- `book_word_count_events (via V18__create_word_count_events_and_split_daily_progress.sql)`
- `notebook_categories (via V13__create_notebook.sql)`
- `notebook_notes (via V13__create_notebook.sql)`
- `scene_items (via V10__create_scene_items.sql)`
- `book_notebook_settings (via V19__add_book_notebook_settings.sql)`
- `chapters (via V3__create_chapters.sql)`
- `book_daily_writing_progress (via V15__add_daily_writing_progress.sql)`
- `scene_participants (via V9__create_scene_participants.sql)`

### Migrations (26 arquivos)

- `V10__create_scene_items.sql`
- `V11__add_scene_planning_notes.sql`
- `V12__add_book_target_word_count.sql`
- `V13__create_notebook.sql`
- `V14__add_notebook_note_status.sql`
- `V15__add_daily_writing_progress.sql`
- `V16__create_book_writing_schedules.sql`
- `V17__create_scene_versions.sql`
- `V18__create_word_count_events_and_split_daily_progress.sql`
- `V19__add_book_notebook_settings.sql`
- `V1__create_books.sql`
- `V20__add_personal_tenant_and_user_timezone_foundation.sql`
- `V21__add_books_tenant_index.sql`
- `V22__add_writing_progress_user_ownership.sql`
- `V23__add_word_count_event_request_fingerprint.sql`
- `V24__daily_progress_dashboard_indexes.sql`
- `V25__add_book_ownership_and_collaborators.sql`
- `V26__backfill_legacy_book_collaborators.sql`
- `V2__create_sections.sql`
- `V3__create_chapters.sql`
- `V4__create_scenes.sql`
- `V5__create_characters.sql`
- `V6__create_locations.sql`
- `V7__create_items.sql`
- `V8__add_scene_planning_fields.sql`
- `V9__create_scene_participants.sql`

---

## 3. Análise Arquitetural

| Aspecto | Status | Observação |
|---------|--------|-----------|
| Arquitetura em camadas | ✅ | controller=✅  service=✅  repository=✅ |
| Testes automatizados | ✅ | 45 Java, 23 JS/TS, 0 Python |
| Migrations versionadas | ✅ | 26 migration(s) |
| Logging | ❌ | não detectado |
| Autenticação / Segurança | ❌ | não detectado |
| DTOs / Separação de dados | ✅ | classes *DTO / *Request / *Response detectadas |
| Tratamento global de exceções | ✅ | @ControllerAdvice / @ExceptionHandler detectado |
| Documentação de API (OpenAPI) | ❌ | não detectado |
| Variáveis de ambiente | ✅ | .env / @Value / os.environ detectado |
| Dockerfile / docker-compose | ✅ | presente |

---

## 4. Contribuição por Usuário

### Resumo

| Usuário | Commits (main) | Commits (GitHub API) | Linhas adicionadas | Linhas no código atual | % código atual |
|---------|---------------|---------------------|-------------------|----------------------|----------------|
| Carlos Henrique Germano Alves (@carloshgalves) | 212 | **270** | 79.669 | 42.248 | 100% |
| @github-classroom[bot] | 3 | **0** | 4.488 | 0 | 0% |

### Contribuição por Camada

| Camada | Total linhas | Carlos Henrique Germano Alves (@carloshgalves) | @github-classroom[bot] |
|--------|-------------|---------|---------|
| Controller | 2.994 | 100% | 0% |
| Frontend | 13.093 | 100% | 0% |
| Repository | 1.183 | 100% | 0% |
| Service | 9.928 | 100% | 0% |
| Test | 7.257 | 100% | 0% |

---

## 5. Contribuição por Funcionalidade

Baseado em `git blame` nos arquivos de controller e service.

| Arquivo | Total linhas | Carlos Henrique Germano Alves (@carloshgalves) | @github-classroom[bot] |
|---------|-------------|---------|---------|
| `SceneContentServiceIntegrationTest.java` | 747 | 100% | 0% |
| `BookExportService.java` | 726 | 100% | 0% |
| `SceneService.java` | 620 | 100% | 0% |
| `WordCountEventServiceIntegrationTest.java` | 540 | 100% | 0% |
| `WritingScheduleIntegrationTest.java` | 513 | 100% | 0% |
| `NotebookControllerIntegrationTest.java` | 502 | 100% | 0% |
| `DailyWritingProgressIntegrationTest.java` | 473 | 100% | 0% |
| `ControllerContractIntegrationTest.java` | 463 | 100% | 0% |
| `UserDashboardServiceIntegrationTest.java` | 443 | 100% | 0% |
| `BookDashboardService.java` | 421 | 100% | 0% |
| `TipTapDocxRenderer.java` | 355 | 100% | 0% |
| `WritingScheduleService.java` | 351 | 100% | 0% |
| `TipTapMarkdownRenderer.java` | 339 | 100% | 0% |
| `UserDashboardService.java` | 337 | 100% | 0% |
| `NotebookService.java` | 313 | 100% | 0% |
| `DailyWritingProgressService.java` | 272 | 100% | 0% |
| `BookDashboardControllerIntegrationTest.java` | 243 | 100% | 0% |
| `V22__add_writing_progress_user_ownership.sql` | 226 | 100% | 0% |
| `SceneVersionService.java` | 206 | 100% | 0% |
| `BookDashboardServiceIntegrationTest.java` | 206 | 100% | 0% |
| `TipTapPlainTextRenderer.java` | 205 | 100% | 0% |
| `ScenePlanningAndItemServiceIntegrationTest.java` | 196 | 100% | 0% |
| `CharacterService.java` | 182 | 100% | 0% |
| `ItemService.java` | 179 | 100% | 0% |
| `UserDashboardControllerIntegrationTest.java` | 161 | 100% | 0% |
| `BookDashboardWritingProgressPeriodIntegrationTest.java` | 158 | 100% | 0% |
| `WordCountEventService.java` | 156 | 100% | 0% |
| `ChapterService.java` | 154 | 100% | 0% |
| `LocationService.java` | 152 | 100% | 0% |
| `BookSectionService.java` | 149 | 100% | 0% |
| `BookService.java` | 144 | 100% | 0% |
| `BookCollaboratorService.java` | 138 | 100% | 0% |
| `WritingDayResolverTest.java` | 128 | 100% | 0% |
| `OutlineService.java` | 127 | 100% | 0% |
| `WordCountRequestFingerprint.java` | 122 | 100% | 0% |
| `SceneDeletionLedgerService.java` | 106 | 100% | 0% |
| `ScenePlanningServiceIntegrationTest.java` | 106 | 100% | 0% |
| `NotebookController.java` | 99 | 100% | 0% |
| `BookExportController.java` | 93 | 100% | 0% |
| `ItemOwnerServiceIntegrationTest.java` | 90 | 100% | 0% |
| `BookAccessService.java` | 86 | 100% | 0% |
| `WritingProgressPeriod.java` | 80 | 100% | 0% |
| `V16__create_book_writing_schedules.sql` | 76 | 100% | 0% |
| `V20__add_personal_tenant_and_user_timezone_foundation.sql` | 72 | 100% | 0% |
| `SceneController.java` | 71 | 100% | 0% |
| `BookDashboardAdditionalIntegrationTest.java` | 69 | 100% | 0% |
| `LocationController.java` | 61 | 100% | 0% |
| `CharacterController.java` | 61 | 100% | 0% |
| `V18__create_word_count_events_and_split_daily_progress.sql` | 60 | 100% | 0% |
| `ItemController.java` | 58 | 100% | 0% |
| `BookController.java` | 58 | 100% | 0% |
| `V25__add_book_ownership_and_collaborators.sql` | 55 | 100% | 0% |
| `WordCountServiceTest.java` | 55 | 100% | 0% |
| `SceneVersionController.java` | 54 | 100% | 0% |
| `BookSectionController.java` | 53 | 100% | 0% |
| `ChapterController.java` | 53 | 100% | 0% |
| `BookCollaboratorController.java` | 49 | 100% | 0% |
| `BookDashboardController.java` | 48 | 100% | 0% |
| `BookTargetWordCountIntegrationTest.java` | 45 | 100% | 0% |
| `ScenePlanningCompletenessService.java` | 40 | 100% | 0% |
| `PingControllerTest.java` | 40 | 100% | 0% |
| `V26__backfill_legacy_book_collaborators.sql` | 32 | 100% | 0% |
| `ExportFileNameService.java` | 31 | 100% | 0% |
| `CurrentUserMembershipService.java` | 31 | 100% | 0% |
| `WritingDayResolver.java` | 31 | 100% | 0% |
| `WordCountService.java` | 27 | 100% | 0% |
| `OutlineController.java` | 26 | 100% | 0% |
| `PingController.java` | 26 | 100% | 0% |
| `UserDashboardController.java` | 25 | 100% | 0% |
| `V13__create_notebook.sql` | 25 | 100% | 0% |
| `WritingProgressPeriodTest.java` | 23 | 100% | 0% |
| `WordCountEventCommand.java` | 22 | 100% | 0% |
| `V5__create_characters.sql` | 21 | 100% | 0% |
| `V17__create_scene_versions.sql` | 20 | 100% | 0% |
| `V15__add_daily_writing_progress.sql` | 18 | 100% | 0% |
| `V7__create_items.sql` | 17 | 100% | 0% |
| `V4__create_scenes.sql` | 17 | 100% | 0% |
| `V6__create_locations.sql` | 15 | 100% | 0% |
| `V3__create_chapters.sql` | 13 | 100% | 0% |
| `IWriteApplication.java` | 12 | 100% | 0% |
| `V19__add_book_notebook_settings.sql` | 12 | 100% | 0% |
| `V2__create_sections.sql` | 11 | 100% | 0% |
| `WordCountEventConflictException.java` | 10 | 100% | 0% |
| `V1__create_books.sql` | 9 | 100% | 0% |
| `V8__add_scene_planning_fields.sql` | 9 | 100% | 0% |
| `ResourceNotFoundException.java` | 8 | 100% | 0% |
| `V10__create_scene_items.sql` | 8 | 100% | 0% |
| `V9__create_scene_participants.sql` | 8 | 100% | 0% |
| `WordCountEventRecordResult.java` | 6 | 100% | 0% |
| `BookCollaboratorGrantResult.java` | 6 | 100% | 0% |
| `V24__daily_progress_dashboard_indexes.sql` | 5 | 100% | 0% |
| `V14__add_notebook_note_status.sql` | 5 | 100% | 0% |
| `V23__add_word_count_event_request_fingerprint.sql` | 3 | 100% | 0% |
| `V12__add_book_target_word_count.sql` | 2 | 100% | 0% |
| `V11__add_scene_planning_notes.sql` | 2 | 100% | 0% |
| `V21__add_books_tenant_index.sql` | 1 | 100% | 0% |

---

*Relatório gerado automaticamente em 2026-06-25.*
*Os dados de contribuição são baseados em `git log --numstat` (linhas adicionadas) e `git blame` (linhas no código atual), excluindo commits de merge.*