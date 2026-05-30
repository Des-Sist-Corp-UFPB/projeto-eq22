package com.iwrite.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ControllerContractIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void getDashboardReturnsBookMetrics() throws Exception {
        var book = createBook("HTTP dashboard", 10);
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        createScene(chapter, "Scene", SceneStatus.WRITTEN, 0, wordText(4));

        mockMvc.perform(get("/api/books/{bookId}/dashboard", book.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookId").value(book.id().toString()))
                .andExpect(jsonPath("$.title").value("HTTP dashboard"))
                .andExpect(jsonPath("$.totalWordCount").value(4))
                .andExpect(jsonPath("$.targetWordCount").value(10))
                .andExpect(jsonPath("$.remainingWordCount").value(6))
                .andExpect(jsonPath("$.totalSections").value(1))
                .andExpect(jsonPath("$.totalChapters").value(1))
                .andExpect(jsonPath("$.totalScenes").value(1));
    }

    @Test
    void getDashboardReturnsNotFoundForMissingBook() throws Exception {
        UUID missingBookId = UUID.randomUUID();

        mockMvc.perform(get("/api/books/{bookId}/dashboard", missingBookId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Book not found"))));
    }

    @Test
    void patchScenePlanningUpdatesLinksAndFields() throws Exception {
        StoryWorld world = createStoryWorld("HTTP planning");

        mockMvc.perform(patch("/api/scenes/{sceneId}/planning", world.scene().id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "goal", "Goal",
                                "conflict", "Conflict",
                                "outcome", "Outcome",
                                "planningNotes", "Notes",
                                "povCharacterId", world.character().id(),
                                "participantCharacterIds", List.of(world.character().id()),
                                "mainLocationId", world.location().id(),
                                "itemIds", List.of(world.item().id())
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goal").value("Goal"))
                .andExpect(jsonPath("$.conflict").value("Conflict"))
                .andExpect(jsonPath("$.outcome").value("Outcome"))
                .andExpect(jsonPath("$.planningNotes").value("Notes"))
                .andExpect(jsonPath("$.povCharacter.id").value(world.character().id().toString()))
                .andExpect(jsonPath("$.mainLocation.id").value(world.location().id().toString()))
                .andExpect(jsonPath("$.participantCharacters[0].id").value(world.character().id().toString()))
                .andExpect(jsonPath("$.items[0].id").value(world.item().id().toString()));
    }

    @Test
    void patchScenePlanningValidatesRequiredCollections() throws Exception {
        StoryWorld world = createStoryWorld("HTTP planning validation");

        mockMvc.perform(patch("/api/scenes/{sceneId}/planning", world.scene().id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "goal", "Goal",
                                "itemIds", List.of()
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("participantCharacterIds"))));
    }

    @Test
    void patchScenePlanningReturnsNotFoundForMissingScene() throws Exception {
        mockMvc.perform(patch("/api/scenes/{sceneId}/planning", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "participantCharacterIds", List.of(),
                                "itemIds", List.of()
                        ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Scene not found"))));
    }

    @Test
    void patchSceneContentPersistsJsonAndRecalculatesWordCount() throws Exception {
        StoryWorld world = createStoryWorld("HTTP content");

        mockMvc.perform(patch("/api/scenes/{sceneId}/content", world.scene().id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "contentJson", "{\"type\":\"doc\"}",
                                "contentText", "one two three four"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentJson").value("{\"type\":\"doc\"}"))
                .andExpect(jsonPath("$.contentText").value("one two three four"))
                .andExpect(jsonPath("$.wordCount").value(4));
    }

    @Test
    void patchSceneContentReturnsNotFoundForMissingScene() throws Exception {
        mockMvc.perform(patch("/api/scenes/{sceneId}/content", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "contentJson", "{}",
                                "contentText", "words"
                        ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Scene not found"))));
    }

    @Test
    void patchItemUpdatesOwnerAndReturnsOwnerSummary() throws Exception {
        StoryWorld world = createStoryWorld("HTTP item");

        mockMvc.perform(patch("/api/items/{itemId}", world.item().id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Updated item",
                                "currentOwnerCharacterId", world.character().id()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated item"))
                .andExpect(jsonPath("$.currentOwnerCharacterId").value(world.character().id().toString()))
                .andExpect(jsonPath("$.currentOwnerCharacter.name").value(world.character().name()));
    }

    @Test
    void patchItemRejectsBlankName() throws Exception {
        StoryWorld world = createStoryWorld("HTTP item validation");

        mockMvc.perform(patch("/api/items/{itemId}", world.item().id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("name must not be blank"))));
    }

    @Test
    void patchItemReturnsNotFoundForMissingItem() throws Exception {
        mockMvc.perform(patch("/api/items/{itemId}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Updated"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Item not found"))));
    }

    @Test
    void patchBookTargetWordCountUpdatesAndClearsTarget() throws Exception {
        var book = createBook("HTTP target");

        mockMvc.perform(patch("/api/books/{bookId}", book.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "targetWordCount", 120000,
                                "dailyTargetWordCount", 1000
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetWordCount").value(120000))
                .andExpect(jsonPath("$.dailyTargetWordCount").value(1000));

        mockMvc.perform(patch("/api/books/{bookId}", book.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetWordCount\":null,\"dailyTargetWordCount\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetWordCount").value(nullValue()))
                .andExpect(jsonPath("$.dailyTargetWordCount").value(nullValue()));
    }

    @Test
    void patchBookRejectsInvalidTargetWordCount() throws Exception {
        var book = createBook("HTTP invalid target");

        mockMvc.perform(patch("/api/books/{bookId}", book.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("targetWordCount", 0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("targetWordCount"))));

        mockMvc.perform(patch("/api/books/{bookId}", book.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("targetWordCount", -1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("targetWordCount"))));

        mockMvc.perform(patch("/api/books/{bookId}", book.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("dailyTargetWordCount", 0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("dailyTargetWordCount"))));

        mockMvc.perform(patch("/api/books/{bookId}", book.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("dailyTargetWordCount", -1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("dailyTargetWordCount"))));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
