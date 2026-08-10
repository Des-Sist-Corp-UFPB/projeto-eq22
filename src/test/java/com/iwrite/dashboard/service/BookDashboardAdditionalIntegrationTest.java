package com.iwrite.dashboard.service;

import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.dashboard.dto.BookDashboardResponse;
import com.iwrite.scene.dto.ScenePlanningRequest;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookDashboardAdditionalIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private BookDashboardService dashboardService;

    @Test
    void rejectsMissingBook() {
        UUID missingBookId = UUID.randomUUID();

        assertThatThrownBy(() -> dashboardService.getDashboard(missingBookId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Book not found");
    }

    @Test
    void positiveTargetReportsRemainingWithoutExceededCount() {
        var book = createBook("Target under", 100);
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        createScene(chapter, "Scene", SceneStatus.DRAFT, 0, wordText(25));

        BookDashboardResponse dashboard = dashboardService.getDashboard(book.id());

        assertThat(dashboard.totalWordCount()).isEqualTo(25);
        assertThat(dashboard.targetWordCount()).isEqualTo(100);
        assertThat(dashboard.remainingWordCount()).isEqualTo(75);
        assertThat(dashboard.exceededTargetWordCount()).isZero();
        assertThat(dashboard.wordCountProgressPercent()).isEqualTo(25.0);
    }

    @Test
    void povOnlyDoesNotCountAsCharacterUsage() {
        StoryWorld world = createStoryWorld("pov usage");
        sceneService.updatePlanning(world.scene().id(), new ScenePlanningRequest(
                "Goal",
                "Conflict",
                "Outcome",
                null,
                world.character().id(),
                List.of(),
                null,
                List.of()
        ));

        BookDashboardResponse dashboard = dashboardService.getDashboard(world.book().id());

        assertThat(dashboard.povStats()).singleElement().satisfies(pov -> {
            assertThat(pov.characterId()).isEqualTo(world.character().id());
            assertThat(pov.scenesCount()).isEqualTo(1);
        });
        assertThat(dashboard.mostUsedCharacters()).isEmpty();
    }
}
