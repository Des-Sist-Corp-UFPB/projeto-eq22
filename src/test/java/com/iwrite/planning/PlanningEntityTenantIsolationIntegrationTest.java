package com.iwrite.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.book.dto.BookResponse;
import com.iwrite.chapter.dto.ChapterResponse;
import com.iwrite.character.dto.CharacterResponse;
import com.iwrite.character.entity.Character;
import com.iwrite.character.repository.CharacterRepository;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.item.dto.ItemRequest;
import com.iwrite.item.dto.ItemResponse;
import com.iwrite.item.dto.ItemUpdateRequest;
import com.iwrite.item.entity.Item;
import com.iwrite.item.repository.ItemRepository;
import com.iwrite.location.dto.LocationResponse;
import com.iwrite.location.entity.Location;
import com.iwrite.location.repository.LocationRepository;
import com.iwrite.scene.dto.ScenePlanningRequest;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.scene.entity.Scene;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.scene.repository.SceneRepository;
import com.iwrite.section.dto.BookSectionResponse;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.SwitchableCurrentUserProvider;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.repository.TenantRepository;
import com.iwrite.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(PlanningEntityTenantIsolationIntegrationTest.CurrentUserTestConfiguration.class)
class PlanningEntityTenantIsolationIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SwitchableCurrentUserProvider currentUserProvider;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private CharacterRepository characterRepository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private SceneRepository sceneRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private Identity tenantA;
    private Identity tenantB;
    private PlanningWorld worldA1;
    private PlanningWorld worldA2;
    private PlanningWorld worldB;

    @BeforeEach
    void setUpTenantPlanningData() {
        currentUserProvider.reset();
        tenantA = createIdentity("Tenant A", "planning-tenant-a@iwrite.local");
        tenantB = createIdentity("Tenant B", "planning-tenant-b@iwrite.local");

        switchTo(tenantA);
        worldA1 = createPlanningWorld("A1");
        worldA2 = createPlanningWorld("A2");
        switchTo(tenantB);
        worldB = createPlanningWorld("B");
        switchTo(tenantA);

        sceneService.updatePlanning(worldA1.scene().id(), planningRequest(
                "Baseline goal",
                "Baseline conflict",
                "Baseline outcome",
                "Baseline notes",
                worldA1.character().id(),
                List.of(worldA1.character().id()),
                worldA1.location().id(),
                List.of(worldA1.item().id())
        ));
    }

    @AfterEach
    void resetIdentity() {
        currentUserProvider.reset();
    }

    @Test
    void characterCrudAndListsAreTenantScoped() throws Exception {
        CharacterResponse created = createCharacter(worldA1.book(), "Character owner CRUD");

        mockMvc.perform(get("/api/books/{bookId}/characters", worldA1.book().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + worldA1.character().id() + "')].name", hasItem("Character A1")))
                .andExpect(jsonPath("$[?(@.id == '" + worldB.character().id() + "')]").isEmpty());
        assertForeignAndMissingBookLists("characters", "Character");

        mockMvc.perform(get("/api/characters/{id}", created.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Character owner CRUD"));
        mockMvc.perform(patch("/api/characters/{id}", created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Character updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Character updated"));
        mockMvc.perform(delete("/api/characters/{id}", created.id())).andExpect(status().isNoContent());
        assertThat(characterRepository.findById(created.id())).isEmpty();

        String foreignName = characterRepository.findById(worldB.character().id()).orElseThrow().getName();
        MvcResult foreign = assertNotFound(get("/api/characters/{id}", worldB.character().id()), "Character not found");
        MvcResult missing = assertNotFound(get("/api/characters/{id}", UUID.randomUUID()), "Character not found");
        assertEquivalentNotFound(foreign, missing);
        assertNotFound(patch("/api/characters/{id}", worldB.character().id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Compromised\"}"), "Character not found");
        assertNotFound(delete("/api/characters/{id}", worldB.character().id()), "Character not found");
        assertThat(characterRepository.findById(worldB.character().id()))
                .hasValueSatisfying(character -> assertThat(character.getName()).isEqualTo(foreignName));

        long foreignCount = countCharacters(worldB.book().id());
        assertNotFound(post("/api/books/{bookId}/characters", worldB.book().id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Foreign creation\"}"), "Book not found");
        assertThat(countCharacters(worldB.book().id())).isEqualTo(foreignCount);
    }

    @Test
    void locationCrudAndListsAreTenantScoped() throws Exception {
        LocationResponse created = createLocation(worldA1.book(), "Location owner CRUD");

        mockMvc.perform(get("/api/books/{bookId}/locations", worldA1.book().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + worldA1.location().id() + "')].name", hasItem("Location A1")))
                .andExpect(jsonPath("$[?(@.id == '" + worldB.location().id() + "')]").isEmpty());
        assertForeignAndMissingBookLists("locations", "Location");

        mockMvc.perform(get("/api/locations/{id}", created.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Location owner CRUD"));
        mockMvc.perform(patch("/api/locations/{id}", created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Location updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Location updated"));
        mockMvc.perform(delete("/api/locations/{id}", created.id())).andExpect(status().isNoContent());
        assertThat(locationRepository.findById(created.id())).isEmpty();

        String foreignName = locationRepository.findById(worldB.location().id()).orElseThrow().getName();
        MvcResult foreign = assertNotFound(get("/api/locations/{id}", worldB.location().id()), "Location not found");
        MvcResult missing = assertNotFound(get("/api/locations/{id}", UUID.randomUUID()), "Location not found");
        assertEquivalentNotFound(foreign, missing);
        assertNotFound(patch("/api/locations/{id}", worldB.location().id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Compromised\"}"), "Location not found");
        assertNotFound(delete("/api/locations/{id}", worldB.location().id()), "Location not found");
        assertThat(locationRepository.findById(worldB.location().id()))
                .hasValueSatisfying(location -> assertThat(location.getName()).isEqualTo(foreignName));

        long foreignCount = countLocations(worldB.book().id());
        assertNotFound(post("/api/books/{bookId}/locations", worldB.book().id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Foreign creation\"}"), "Book not found");
        assertThat(countLocations(worldB.book().id())).isEqualTo(foreignCount);
    }

    @Test
    void itemCrudAndListsAreTenantScoped() throws Exception {
        ItemResponse created = createItem(worldA1.book(), "Item owner CRUD");

        mockMvc.perform(get("/api/books/{bookId}/items", worldA1.book().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + worldA1.item().id() + "')].name", hasItem("Item A1")))
                .andExpect(jsonPath("$[?(@.id == '" + worldB.item().id() + "')]").isEmpty());
        assertForeignAndMissingBookLists("items", "Item");

        mockMvc.perform(get("/api/items/{id}", created.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Item owner CRUD"));
        mockMvc.perform(patch("/api/items/{id}", created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Item updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Item updated"));
        mockMvc.perform(delete("/api/items/{id}", created.id())).andExpect(status().isNoContent());
        assertThat(itemRepository.findById(created.id())).isEmpty();

        ItemState foreignState = itemState(worldB.item().id());
        MvcResult foreign = assertNotFound(get("/api/items/{id}", worldB.item().id()), "Item not found");
        MvcResult missing = assertNotFound(get("/api/items/{id}", UUID.randomUUID()), "Item not found");
        assertEquivalentNotFound(foreign, missing);
        assertNotFound(patch("/api/items/{id}", worldB.item().id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Compromised\"}"), "Item not found");
        assertNotFound(delete("/api/items/{id}", worldB.item().id()), "Item not found");
        assertThat(itemState(worldB.item().id())).isEqualTo(foreignState);

        long foreignCount = countItems(worldB.book().id());
        assertNotFound(post("/api/books/{bookId}/items", worldB.book().id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Foreign creation\"}"), "Book not found");
        assertThat(countItems(worldB.book().id())).isEqualTo(foreignCount);
    }

    @Test
    void ownerCanAssignAndClearScenePlanningAssociations() {
        PlanningState assigned = planningState(worldA1.scene().id());
        assertThat(assigned.povCharacterId()).isEqualTo(worldA1.character().id());
        assertThat(assigned.mainLocationId()).isEqualTo(worldA1.location().id());
        assertThat(assigned.participantCharacterIds()).containsExactly(worldA1.character().id());
        assertThat(assigned.itemIds()).containsExactly(worldA1.item().id());

        sceneService.updatePlanning(worldA1.scene().id(), planningRequest(
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                List.of()
        ));

        PlanningState cleared = planningState(worldA1.scene().id());
        assertThat(cleared.goal()).isNull();
        assertThat(cleared.povCharacterId()).isNull();
        assertThat(cleared.mainLocationId()).isNull();
        assertThat(cleared.participantCharacterIds()).isEmpty();
        assertThat(cleared.itemIds()).isEmpty();
    }

    @Test
    void foreignPlanningResourcesReturnNotFoundWithoutPartialMutation() throws Exception {
        PlanningState baseline = planningState(worldA1.scene().id());

        assertPlanningFailure(planningRequest(
                "Changed goal", null, null, null,
                worldB.character().id(), List.of(worldA1.character().id()), worldA1.location().id(), List.of(worldA1.item().id())
        ), 404, "Character not found");
        assertThat(planningState(worldA1.scene().id())).isEqualTo(baseline);

        assertPlanningFailure(planningRequest(
                "Changed goal", null, null, null,
                worldA1.character().id(), List.of(worldA1.character().id()), worldB.location().id(), List.of(worldA1.item().id())
        ), 404, "Location not found");
        assertThat(planningState(worldA1.scene().id())).isEqualTo(baseline);

        assertPlanningFailure(planningRequest(
                "Changed goal", null, null, null,
                worldA1.character().id(), List.of(worldA1.character().id()), worldA1.location().id(), List.of(worldB.item().id())
        ), 404, "Item not found");
        assertThat(planningState(worldA1.scene().id())).isEqualTo(baseline);

        assertPlanningFailure(planningRequest(
                "Mixed changed goal", "Changed conflict", null, null,
                worldA1.character().id(), List.of(worldA1.character().id(), worldB.character().id()),
                worldA1.location().id(), List.of(worldA1.item().id())
        ), 404, "Character not found");
        assertThat(planningState(worldA1.scene().id())).isEqualTo(baseline);
    }

    @Test
    void sameTenantWrongBookPlanningResourcesReturnBadRequestWithoutMutation() throws Exception {
        PlanningState baseline = planningState(worldA1.scene().id());

        assertPlanningFailure(planningRequest(
                "Changed goal", "Changed conflict", "Changed outcome", "Changed notes",
                worldA2.character().id(), List.of(worldA1.character().id()), worldA1.location().id(), List.of(worldA1.item().id())
        ), 400, "povCharacterId must belong to the same book");
        assertThat(planningState(worldA1.scene().id())).isEqualTo(baseline);

        assertPlanningFailure(planningRequest(
                "Changed goal", "Changed conflict", "Changed outcome", "Changed notes",
                worldA1.character().id(), List.of(worldA2.character().id()), worldA1.location().id(), List.of(worldA1.item().id())
        ), 400, "participantCharacterIds must belong to the same book");
        assertThat(planningState(worldA1.scene().id())).isEqualTo(baseline);

        assertPlanningFailure(planningRequest(
                "Changed goal", "Changed conflict", "Changed outcome", "Changed notes",
                worldA1.character().id(), List.of(worldA1.character().id()), worldA2.location().id(), List.of(worldA1.item().id())
        ), 400, "mainLocationId must belong to the same book");
        assertThat(planningState(worldA1.scene().id())).isEqualTo(baseline);

        assertPlanningFailure(planningRequest(
                "Changed goal", "Changed conflict", "Changed outcome", "Changed notes",
                worldA1.character().id(), List.of(worldA1.character().id()), worldA1.location().id(), List.of(worldA2.item().id())
        ), 400, "itemIds must belong to the same book");
        assertThat(planningState(worldA1.scene().id())).isEqualTo(baseline);
    }

    @Test
    void foreignScenePlanningRemainsBlockedBySceneBoundary() throws Exception {
        PlanningState baseline = planningState(worldA1.scene().id());
        switchTo(tenantB);

        assertNotFound(patch("/api/scenes/{sceneId}/planning", worldA1.scene().id())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(planningRequest(
                        null, null, null, null,
                        worldB.character().id(), List.of(), worldB.location().id(), List.of()
                ))), "Scene not found");

        assertThat(planningState(worldA1.scene().id())).isEqualTo(baseline);
    }

    @Test
    void itemOwnerAssociationIsTenantAndBookScopedBeforeMutation() throws Exception {
        CharacterResponse secondOwner = createCharacter(worldA1.book(), "Second owner");
        ItemResponse created = itemService.create(worldA1.book().id(), new ItemRequest(
                "Owned item", "type", "description", "origin", worldA1.character().id(), "importance", "notes"
        ));
        assertThat(created.currentOwnerCharacterId()).isEqualTo(worldA1.character().id());

        sceneService.updatePlanning(worldA1.scene().id(), planningRequest(
                "Baseline goal",
                "Baseline conflict",
                "Baseline outcome",
                "Baseline notes",
                worldA1.character().id(),
                List.of(worldA1.character().id()),
                worldA1.location().id(),
                List.of(worldA1.item().id(), created.id())
        ));

        mockMvc.perform(patch("/api/items/{id}", created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemOwnerUpdateJson("Owner updated item", secondOwner.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Owner updated item"))
                .andExpect(jsonPath("$.currentOwnerCharacterId").value(secondOwner.id().toString()));
        ItemState validState = itemState(created.id());

        assertNotFound(patch("/api/items/{id}", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content(itemOwnerUpdateJson("Foreign mutation", worldB.character().id())), "Character not found");
        assertThat(itemState(created.id())).isEqualTo(validState);

        mockMvc.perform(patch("/api/items/{id}", created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemOwnerUpdateJson("Wrong-book mutation", worldA2.character().id())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("currentOwnerCharacterId must belong to the same book"))));
        assertThat(itemState(created.id())).isEqualTo(validState);

        long itemCount = countItems(worldA1.book().id());
        assertNotFound(post("/api/books/{bookId}/items", worldA1.book().id())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ItemRequest(
                        "Foreign-owner item", null, null, null, worldB.character().id(), null, null
                ))), "Character not found");
        mockMvc.perform(post("/api/books/{bookId}/items", worldA1.book().id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ItemRequest(
                                "Wrong-book-owner item", null, null, null, worldA2.character().id(), null, null
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("currentOwnerCharacterId must belong to the same book"))));
        assertThat(countItems(worldA1.book().id())).isEqualTo(itemCount);

        mockMvc.perform(patch("/api/items/{id}", created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentOwnerCharacterId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentOwnerCharacterId").doesNotExist());
    }

    @Test
    void referencedPlanningEntitiesReturnConflictOnDeleteAndPreserveState() throws Exception {
        CharacterResponse unreferencedCharacter = createCharacter(worldA1.book(), "Unreferenced character");
        mockMvc.perform(delete("/api/characters/{id}", unreferencedCharacter.id())).andExpect(status().isNoContent());
        assertThat(characterRepository.findById(unreferencedCharacter.id())).isEmpty();

        PlanningState povState = planningState(worldA1.scene().id());
        assertConflict(delete("/api/characters/{id}", worldA1.character().id()), "Character cannot be deleted");
        assertThat(characterRepository.findById(worldA1.character().id())).isPresent();
        assertThat(planningState(worldA1.scene().id())).isEqualTo(povState);

        CharacterResponse participantOnly = createCharacter(worldA1.book(), "Participant only");
        sceneService.updatePlanning(worldA1.scene().id(), planningRequest(
                "Participant goal",
                "Participant conflict",
                "Participant outcome",
                "Participant notes",
                null,
                List.of(participantOnly.id()),
                worldA1.location().id(),
                List.of(worldA1.item().id())
        ));
        PlanningState participantState = planningState(worldA1.scene().id());
        assertConflict(delete("/api/characters/{id}", participantOnly.id()), "Character cannot be deleted");
        assertThat(characterRepository.findById(participantOnly.id())).isPresent();
        assertThat(planningState(worldA1.scene().id())).isEqualTo(participantState);

        CharacterResponse ownerOnly = createCharacter(worldA1.book(), "Owner only");
        ItemResponse ownedItem = createItem(worldA1.book(), "Owner-only item", ownerOnly);
        ItemState ownedItemState = itemState(ownedItem.id());
        assertConflict(delete("/api/characters/{id}", ownerOnly.id()), "Character cannot be deleted");
        assertThat(characterRepository.findById(ownerOnly.id())).isPresent();
        assertThat(itemState(ownedItem.id())).isEqualTo(ownedItemState);

        LocationResponse unreferencedLocation = createLocation(worldA1.book(), "Unreferenced location");
        mockMvc.perform(delete("/api/locations/{id}", unreferencedLocation.id())).andExpect(status().isNoContent());
        assertThat(locationRepository.findById(unreferencedLocation.id())).isEmpty();

        PlanningState locationState = planningState(worldA1.scene().id());
        assertConflict(delete("/api/locations/{id}", worldA1.location().id()), "Location cannot be deleted");
        assertThat(locationRepository.findById(worldA1.location().id())).isPresent();
        assertThat(planningState(worldA1.scene().id())).isEqualTo(locationState);

        ItemResponse unreferencedItem = createItem(worldA1.book(), "Unreferenced item");
        mockMvc.perform(delete("/api/items/{id}", unreferencedItem.id())).andExpect(status().isNoContent());
        assertThat(itemRepository.findById(unreferencedItem.id())).isEmpty();

        PlanningState itemState = planningState(worldA1.scene().id());
        assertConflict(delete("/api/items/{id}", worldA1.item().id()), "Item cannot be deleted");
        assertThat(itemRepository.findById(worldA1.item().id())).isPresent();
        assertThat(planningState(worldA1.scene().id())).isEqualTo(itemState);

        MvcResult foreignCharacter = assertNotFound(delete("/api/characters/{id}", worldB.character().id()), "Character not found");
        MvcResult missingCharacter = assertNotFound(delete("/api/characters/{id}", UUID.randomUUID()), "Character not found");
        assertEquivalentNotFound(foreignCharacter, missingCharacter);
        MvcResult foreignLocation = assertNotFound(delete("/api/locations/{id}", worldB.location().id()), "Location not found");
        MvcResult missingLocation = assertNotFound(delete("/api/locations/{id}", UUID.randomUUID()), "Location not found");
        assertEquivalentNotFound(foreignLocation, missingLocation);
        MvcResult foreignItem = assertNotFound(delete("/api/items/{id}", worldB.item().id()), "Item not found");
        MvcResult missingItem = assertNotFound(delete("/api/items/{id}", UUID.randomUUID()), "Item not found");
        assertEquivalentNotFound(foreignItem, missingItem);
    }

    private PlanningWorld createPlanningWorld(String label) {
        BookResponse book = createBook("Book " + label);
        BookSectionResponse section = createSection(book, "Section " + label);
        ChapterResponse chapter = createChapter(section, "Chapter " + label);
        SceneResponse scene = createScene(chapter, "Scene " + label, SceneStatus.DRAFT, 0, "Scene content " + label);
        CharacterResponse character = createCharacter(book, "Character " + label);
        LocationResponse location = createLocation(book, "Location " + label);
        ItemResponse item = createItem(book, "Item " + label, character);
        return new PlanningWorld(book, scene, character, location, item);
    }

    private Identity createIdentity(String name, String email) {
        Tenant tenant = new Tenant();
        tenant.setName(name);
        tenant.setDefaultTimeZoneId("UTC");
        UUID tenantId = tenantRepository.save(tenant).getId();

        User user = new User();
        user.setDisplayName(name + " User");
        user.setEmail(email);
        user.setTimeZoneId("UTC");
        entityManager.persist(user);
        return new Identity(user.getId(), tenantId);
    }

    private void assertForeignAndMissingBookLists(String resourcePath, String resourceName) throws Exception {
        MvcResult foreign = assertNotFound(
                get("/api/books/{bookId}/" + resourcePath, worldB.book().id()),
                "Book not found"
        );
        MvcResult missing = assertNotFound(
                get("/api/books/{bookId}/" + resourcePath, UUID.randomUUID()),
                "Book not found"
        );
        assertEquivalentNotFound(foreign, missing);
        assertThat(foreign.getResponse().getContentAsString()).doesNotContain(resourceName + " B");
    }

    private void assertPlanningFailure(ScenePlanningRequest request, int expectedStatus, String message) throws Exception {
        mockMvc.perform(patch("/api/scenes/{sceneId}/planning", worldA1.scene().id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.messages", hasItem(containsString(message))));
    }

    private MvcResult assertNotFound(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String message
    ) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.messages", hasItem(containsString(message))))
                .andReturn();
    }

    private MvcResult assertConflict(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String message
    ) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.messages", hasItem(containsString(message))))
                .andReturn();
    }

    private void assertEquivalentNotFound(MvcResult first, MvcResult second) throws Exception {
        assertThat(first.getResponse().getStatus()).isEqualTo(second.getResponse().getStatus());
        assertThat(first.getResponse().getContentType()).isEqualTo(second.getResponse().getContentType());
    }

    private ScenePlanningRequest planningRequest(
            String goal,
            String conflict,
            String outcome,
            String planningNotes,
            UUID povCharacterId,
            List<UUID> participantCharacterIds,
            UUID mainLocationId,
            List<UUID> itemIds
    ) {
        return new ScenePlanningRequest(
                goal,
                conflict,
                outcome,
                planningNotes,
                povCharacterId,
                participantCharacterIds,
                mainLocationId,
                itemIds
        );
    }

    private PlanningState planningState(UUID sceneId) {
        entityManager.flush();
        entityManager.clear();
        Scene scene = sceneRepository.findById(sceneId).orElseThrow();
        return new PlanningState(
                scene.getGoal(),
                scene.getConflict(),
                scene.getOutcome(),
                scene.getPlanningNotes(),
                scene.getPovCharacter() == null ? null : scene.getPovCharacter().getId(),
                scene.getMainLocation() == null ? null : scene.getMainLocation().getId(),
                sortedIds(scene.getParticipantCharacters().stream().map(Character::getId).collect(java.util.stream.Collectors.toSet())),
                sortedIds(scene.getItems().stream().map(Item::getId).collect(java.util.stream.Collectors.toSet()))
        );
    }

    private List<UUID> sortedIds(Set<UUID> ids) {
        return ids.stream().sorted(Comparator.comparing(UUID::toString)).toList();
    }

    private ItemState itemState(UUID itemId) {
        entityManager.flush();
        entityManager.clear();
        Item item = itemRepository.findById(itemId).orElseThrow();
        return new ItemState(
                item.getName(),
                item.getType(),
                item.getDescription(),
                item.getOrigin(),
                item.getCurrentOwnerCharacter() == null ? null : item.getCurrentOwnerCharacter().getId(),
                item.getNarrativeImportance(),
                item.getNotes(),
                sceneIdsContainingItem(itemId)
        );
    }

    private List<UUID> sceneIdsContainingItem(UUID itemId) {
        return sortedIds(sceneRepository.findAll().stream()
                .filter(scene -> scene.getItems().stream().anyMatch(item -> item.getId().equals(itemId)))
                .map(Scene::getId)
                .collect(java.util.stream.Collectors.toSet()));
    }

    private String itemOwnerUpdateJson(String name, UUID ownerId) {
        return """
                {
                  "name": "%s",
                  "type": "mutated type",
                  "description": "mutated description",
                  "origin": "mutated origin",
                  "currentOwnerCharacterId": "%s",
                  "narrativeImportance": "mutated importance",
                  "notes": "mutated notes"
                }
                """.formatted(name, ownerId);
    }

    private long countCharacters(UUID bookId) {
        return characterRepository.findAll().stream()
                .filter(character -> character.getBook().getId().equals(bookId))
                .count();
    }

    private long countLocations(UUID bookId) {
        return locationRepository.findAll().stream()
                .filter(location -> location.getBook().getId().equals(bookId))
                .count();
    }

    private long countItems(UUID bookId) {
        return itemRepository.findAll().stream()
                .filter(item -> item.getBook().getId().equals(bookId))
                .count();
    }

    private void switchTo(Identity identity) {
        currentUserProvider.switchTo(identity.userId(), identity.tenantId(), ZoneId.of("UTC"));
    }

    private record Identity(UUID userId, UUID tenantId) {
    }

    private record PlanningWorld(
            BookResponse book,
            SceneResponse scene,
            CharacterResponse character,
            LocationResponse location,
            ItemResponse item
    ) {
    }

    private record PlanningState(
            String goal,
            String conflict,
            String outcome,
            String planningNotes,
            UUID povCharacterId,
            UUID mainLocationId,
            List<UUID> participantCharacterIds,
            List<UUID> itemIds
    ) {
    }

    private record ItemState(
            String name,
            String type,
            String description,
            String origin,
            UUID ownerCharacterId,
            String narrativeImportance,
            String notes,
            List<UUID> sceneIds
    ) {
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
