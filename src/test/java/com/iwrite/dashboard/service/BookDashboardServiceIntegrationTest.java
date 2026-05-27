package com.iwrite.dashboard.service;

import com.iwrite.book.dto.BookRequest;
import com.iwrite.book.dto.BookUpdateRequest;
import com.iwrite.book.service.BookService;
import com.iwrite.chapter.dto.ChapterRequest;
import com.iwrite.chapter.service.ChapterService;
import com.iwrite.character.dto.CharacterRequest;
import com.iwrite.character.dto.CharacterResponse;
import com.iwrite.character.service.CharacterService;
import com.iwrite.dashboard.dto.BookDashboardResponse;
import com.iwrite.item.dto.ItemRequest;
import com.iwrite.item.service.ItemService;
import com.iwrite.location.dto.LocationRequest;
import com.iwrite.location.dto.LocationResponse;
import com.iwrite.location.service.LocationService;
import com.iwrite.scene.dto.ScenePlanningRequest;
import com.iwrite.scene.dto.SceneRequest;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.scene.service.SceneService;
import com.iwrite.section.dto.BookSectionRequest;
import com.iwrite.section.entity.SectionType;
import com.iwrite.section.service.BookSectionService;
import com.iwrite.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BookDashboardServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private BookService bookService;

    @Autowired
    private BookSectionService sectionService;

    @Autowired
    private ChapterService chapterService;

    @Autowired
    private CharacterService characterService;

    @Autowired
    private LocationService locationService;

    @Autowired
    private ItemService itemService;

    @Autowired
    private SceneService sceneService;

    @Autowired
    private BookDashboardService dashboardService;

    @Test
    void returnsSafeZerosForBookWithoutScenes() {
        UUID bookId = bookService.create(new BookRequest("Livro vazio", null, null, null, null)).id();

        BookDashboardResponse dashboard = dashboardService.getDashboard(bookId);

        assertThat(dashboard.totalWordCount()).isZero();
        assertThat(dashboard.totalScenes()).isZero();
        assertThat(dashboard.totalSections()).isZero();
        assertThat(dashboard.totalChapters()).isZero();
        assertThat(dashboard.targetWordCount()).isNull();
        assertThat(dashboard.remainingWordCount()).isNull();
        assertThat(dashboard.wordCountProgressPercent()).isNull();
        assertThat(dashboard.exceededTargetWordCount()).isNull();
        assertThat(dashboard.scenesByStatus()).hasSize(SceneStatus.values().length);
        assertThat(dashboard.scenesByStatus()).allSatisfy(status -> {
            assertThat(status.scenesCount()).isZero();
            assertThat(status.wordCount()).isZero();
            assertThat(status.scenes()).isEmpty();
        });
        assertThat(dashboard.planningProgress().plannedScenesPercent()).isZero();
    }

    @Test
    void calculatesNarrativeStatsAndExceededWordTarget() {
        UUID bookId = bookService.create(new BookRequest("Livro com dashboard", null, null, null, null)).id();
        UUID sectionId = sectionService.create(bookId, new BookSectionRequest("Parte 1", SectionType.PART, 0)).id();
        UUID chapterId = chapterService.create(sectionId, new ChapterRequest("Capitulo 1", null, 0)).id();

        CharacterResponse adelaide = characterService.create(bookId, characterRequest("Adelaide"));
        CharacterResponse bruno = characterService.create(bookId, characterRequest("Bruno"));
        LocationResponse house = locationService.create(bookId, new LocationRequest("Casa", "Casa", null, null, null, null));
        UUID amuletId = itemService.create(bookId, new ItemRequest("Amuleto", "Relíquia", null, null, adelaide.id(), null, null)).id();

        UUID ideaSceneId = sceneService.create(chapterId, sceneRequest("Cena ideia", SceneStatus.IDEA, 0, words(10))).id();
        UUID writtenSceneId = sceneService.create(chapterId, sceneRequest("Cena escrita", SceneStatus.WRITTEN, 1, words(20))).id();
        UUID draftSceneId = sceneService.create(chapterId, sceneRequest("Cena rascunho", SceneStatus.DRAFT, 2, words(5))).id();

        sceneService.updatePlanning(writtenSceneId, new ScenePlanningRequest(
                "Objetivo",
                "Conflito",
                "Resultado",
                "Notas",
                adelaide.id(),
                List.of(adelaide.id(), bruno.id()),
                house.id(),
                List.of(amuletId)
        ));
        sceneService.updatePlanning(draftSceneId, new ScenePlanningRequest(
                "Objetivo parcial",
                null,
                null,
                null,
                bruno.id(),
                List.of(),
                null,
                List.of(amuletId)
        ));

        BookUpdateRequest targetRequest = new BookUpdateRequest();
        targetRequest.setTargetWordCount(30);
        bookService.update(bookId, targetRequest);

        BookDashboardResponse dashboard = dashboardService.getDashboard(bookId);

        assertThat(dashboard.totalWordCount()).isEqualTo(35);
        assertThat(dashboard.totalSections()).isEqualTo(1);
        assertThat(dashboard.totalChapters()).isEqualTo(1);
        assertThat(dashboard.totalScenes()).isEqualTo(3);
        assertThat(dashboard.planningProgress().plannedScenesCount()).isEqualTo(1);
        assertThat(dashboard.planningProgress().plannedScenesPercent()).isCloseTo(33.333, within(0.01));
        assertThat(dashboard.targetWordCount()).isEqualTo(30);
        assertThat(dashboard.remainingWordCount()).isZero();
        assertThat(dashboard.wordCountProgressPercent()).isCloseTo(116.666, within(0.01));
        assertThat(dashboard.exceededTargetWordCount()).isEqualTo(5);

        assertThat(dashboard.scenesByStatus())
                .filteredOn(status -> status.status() == SceneStatus.IDEA)
                .singleElement()
                .satisfies(status -> {
                    assertThat(status.scenesCount()).isEqualTo(1);
                    assertThat(status.wordCount()).isEqualTo(10);
                    assertThat(status.scenes()).singleElement().satisfies(scene -> assertThat(scene.sceneId()).isEqualTo(ideaSceneId));
                });
        assertThat(dashboard.scenesByStatus())
                .filteredOn(status -> status.status() == SceneStatus.WRITTEN)
                .singleElement()
                .satisfies(status -> {
                    assertThat(status.scenesCount()).isEqualTo(1);
                    assertThat(status.wordCount()).isEqualTo(20);
                });

        assertThat(dashboard.narrativeGaps().scenesWithoutPov()).isEqualTo(1);
        assertThat(dashboard.narrativeGaps().scenesWithoutGoal()).isEqualTo(1);
        assertThat(dashboard.narrativeGaps().scenesWithoutConflict()).isEqualTo(2);
        assertThat(dashboard.narrativeGaps().scenesWithoutOutcome()).isEqualTo(2);
        assertThat(dashboard.narrativeGaps().scenesWithoutMainLocation()).isEqualTo(2);
        assertThat(dashboard.narrativeGaps().scenesWithoutParticipants()).isEqualTo(2);

        assertThat(dashboard.povStats())
                .anySatisfy(pov -> {
                    assertThat(pov.name()).isEqualTo("Adelaide");
                    assertThat(pov.scenesCount()).isEqualTo(1);
                    assertThat(pov.wordCount()).isEqualTo(20);
                })
                .anySatisfy(pov -> {
                    assertThat(pov.name()).isEqualTo("Bruno");
                    assertThat(pov.scenesCount()).isEqualTo(1);
                    assertThat(pov.wordCount()).isEqualTo(5);
                });
        assertThat(dashboard.mostUsedCharacters())
                .hasSize(2)
                .anySatisfy(usage -> {
                    assertThat(usage.name()).isEqualTo("Adelaide");
                    assertThat(usage.scenesCount()).isEqualTo(1);
                })
                .anySatisfy(usage -> {
                    assertThat(usage.name()).isEqualTo("Bruno");
                    assertThat(usage.scenesCount()).isEqualTo(1);
                });
        assertThat(dashboard.mostUsedLocations()).singleElement().satisfies(usage -> {
            assertThat(usage.name()).isEqualTo("Casa");
            assertThat(usage.scenesCount()).isEqualTo(1);
        });
        assertThat(dashboard.mostUsedItems()).singleElement().satisfies(usage -> {
            assertThat(usage.name()).isEqualTo("Amuleto");
            assertThat(usage.scenesCount()).isEqualTo(2);
        });
    }

    private CharacterRequest characterRequest(String name) {
        return new CharacterRequest(name, null, null, null, null, null, null, null, null, null, null, null);
    }

    private SceneRequest sceneRequest(String title, SceneStatus status, int sortOrder, String contentText) {
        return new SceneRequest(title, null, status, sortOrder, "{}", contentText);
    }

    private String words(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(index -> "palavra" + index)
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
