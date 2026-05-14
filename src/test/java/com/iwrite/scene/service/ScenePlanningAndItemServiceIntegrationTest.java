package com.iwrite.scene.service;

import com.iwrite.book.dto.BookRequest;
import com.iwrite.book.service.BookService;
import com.iwrite.chapter.dto.ChapterRequest;
import com.iwrite.chapter.service.ChapterService;
import com.iwrite.character.dto.CharacterRequest;
import com.iwrite.character.dto.CharacterResponse;
import com.iwrite.character.service.CharacterService;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.item.dto.ItemRequest;
import com.iwrite.item.dto.ItemResponse;
import com.iwrite.item.dto.ItemUpdateRequest;
import com.iwrite.item.service.ItemService;
import com.iwrite.location.dto.LocationRequest;
import com.iwrite.location.dto.LocationResponse;
import com.iwrite.location.service.LocationService;
import com.iwrite.scene.dto.ScenePlanningRequest;
import com.iwrite.scene.dto.SceneRequest;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.section.dto.BookSectionRequest;
import com.iwrite.section.entity.SectionType;
import com.iwrite.section.service.BookSectionService;
import com.iwrite.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenePlanningAndItemServiceIntegrationTest extends PostgresIntegrationTest {

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

    @Test
    void rejectsDuplicatePlanningLinksAndForeignBookLinks() {
        TestWorld primary = createWorld("Livro principal");
        TestWorld other = createWorld("Outro livro");

        assertThatThrownBy(() -> sceneService.updatePlanning(primary.sceneId, planningRequest(
                primary.character.id(),
                List.of(primary.character.id(), primary.character.id()),
                primary.location.id(),
                List.of(primary.item.id())
        ))).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("participantCharacterIds must not contain duplicate IDs");

        assertThatThrownBy(() -> sceneService.updatePlanning(primary.sceneId, planningRequest(
                primary.character.id(),
                List.of(primary.character.id()),
                primary.location.id(),
                List.of(primary.item.id(), primary.item.id())
        ))).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("itemIds must not contain duplicate IDs");

        assertThatThrownBy(() -> sceneService.updatePlanning(primary.sceneId, planningRequest(
                other.character.id(),
                List.of(primary.character.id()),
                primary.location.id(),
                List.of(primary.item.id())
        ))).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("povCharacterId must belong to the same book");

        assertThatThrownBy(() -> sceneService.updatePlanning(primary.sceneId, planningRequest(
                primary.character.id(),
                List.of(other.character.id()),
                primary.location.id(),
                List.of(primary.item.id())
        ))).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("participantCharacterIds must belong to the same book");

        assertThatThrownBy(() -> sceneService.updatePlanning(primary.sceneId, planningRequest(
                primary.character.id(),
                List.of(primary.character.id()),
                other.location.id(),
                List.of(primary.item.id())
        ))).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("mainLocationId must belong to the same book");

        assertThatThrownBy(() -> sceneService.updatePlanning(primary.sceneId, planningRequest(
                primary.character.id(),
                List.of(primary.character.id()),
                primary.location.id(),
                List.of(other.item.id())
        ))).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("itemIds must belong to the same book");
    }

    @Test
    void planningUpdateDoesNotChangeSceneContent() {
        TestWorld world = createWorld("Livro planejamento");
        SceneResponse before = sceneService.findById(world.sceneId);

        SceneResponse after = sceneService.updatePlanning(world.sceneId, planningRequest(
                world.character.id(),
                List.of(world.character.id()),
                world.location.id(),
                List.of(world.item.id())
        ));

        assertThat(after.contentJson()).isEqualTo(before.contentJson());
        assertThat(after.contentText()).isEqualTo(before.contentText());
        assertThat(after.wordCount()).isEqualTo(before.wordCount());
        assertThat(after.goal()).isEqualTo("Objetivo");
    }

    @Test
    void itemOwnerUpdateCanPreserveClearAndRejectForeignOwner() {
        TestWorld world = createWorld("Livro item");
        TestWorld other = createWorld("Outro livro item");

        ItemUpdateRequest omittedOwner = new ItemUpdateRequest();
        omittedOwner.setType("Documento");
        ItemResponse preserved = itemService.update(world.item.id(), omittedOwner);
        assertThat(preserved.currentOwnerCharacterId()).isEqualTo(world.character.id());
        assertThat(preserved.currentOwnerCharacter().name()).isEqualTo(world.character.name());

        ItemUpdateRequest clearOwner = new ItemUpdateRequest();
        clearOwner.setCurrentOwnerCharacterId(null);
        ItemResponse cleared = itemService.update(world.item.id(), clearOwner);
        assertThat(cleared.currentOwnerCharacterId()).isNull();
        assertThat(cleared.currentOwnerCharacter()).isNull();

        ItemUpdateRequest foreignOwner = new ItemUpdateRequest();
        foreignOwner.setCurrentOwnerCharacterId(other.character.id());
        assertThatThrownBy(() -> itemService.update(world.item.id(), foreignOwner))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("currentOwnerCharacterId must belong to the same book");
    }

    private TestWorld createWorld(String bookTitle) {
        UUID bookId = bookService.create(new BookRequest(bookTitle, null, null, null, null)).id();
        UUID sectionId = sectionService.create(bookId, new BookSectionRequest("Parte", SectionType.PART, 0)).id();
        UUID chapterId = chapterService.create(sectionId, new ChapterRequest("Capitulo", null, 0)).id();
        CharacterResponse character = characterService.create(bookId, characterRequest("Personagem " + bookTitle));
        LocationResponse location = locationService.create(bookId, new LocationRequest("Lugar " + bookTitle, null, null, null, null, null));
        ItemResponse item = itemService.create(bookId, new ItemRequest("Item " + bookTitle, null, null, null, character.id(), null, null));
        UUID sceneId = sceneService.create(chapterId, new SceneRequest("Cena", null, SceneStatus.DRAFT, 0, "{\"type\":\"doc\"}", "conteudo da cena")).id();

        return new TestWorld(bookId, sceneId, character, location, item);
    }

    private CharacterRequest characterRequest(String name) {
        return new CharacterRequest(name, null, null, null, null, null, null, null, null, null, null, null);
    }

    private ScenePlanningRequest planningRequest(
            UUID povCharacterId,
            List<UUID> participantCharacterIds,
            UUID mainLocationId,
            List<UUID> itemIds
    ) {
        return new ScenePlanningRequest(
                "Objetivo",
                "Conflito",
                "Resultado",
                "Notas",
                povCharacterId,
                participantCharacterIds,
                mainLocationId,
                itemIds
        );
    }

    private record TestWorld(
            UUID bookId,
            UUID sceneId,
            CharacterResponse character,
            LocationResponse location,
            ItemResponse item
    ) {
    }
}
