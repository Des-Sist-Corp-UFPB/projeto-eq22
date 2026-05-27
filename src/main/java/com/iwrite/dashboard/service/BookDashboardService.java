package com.iwrite.dashboard.service;

import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookService;
import com.iwrite.chapter.entity.Chapter;
import com.iwrite.chapter.repository.ChapterRepository;
import com.iwrite.character.entity.Character;
import com.iwrite.dashboard.dto.BookDashboardResponse;
import com.iwrite.dashboard.dto.DashboardSceneSummaryResponse;
import com.iwrite.dashboard.dto.EntityUsageResponse;
import com.iwrite.dashboard.dto.NarrativeGapsResponse;
import com.iwrite.dashboard.dto.PlanningProgressResponse;
import com.iwrite.dashboard.dto.PovStatsResponse;
import com.iwrite.dashboard.dto.StatusCountResponse;
import com.iwrite.item.entity.Item;
import com.iwrite.location.entity.Location;
import com.iwrite.scene.entity.Scene;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.scene.repository.SceneRepository;
import com.iwrite.section.entity.BookSection;
import com.iwrite.section.repository.BookSectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BookDashboardService {

    private final BookService bookService;
    private final BookSectionRepository sectionRepository;
    private final ChapterRepository chapterRepository;
    private final SceneRepository sceneRepository;

    public BookDashboardService(
            BookService bookService,
            BookSectionRepository sectionRepository,
            ChapterRepository chapterRepository,
            SceneRepository sceneRepository
    ) {
        this.bookService = bookService;
        this.sectionRepository = sectionRepository;
        this.chapterRepository = chapterRepository;
        this.sceneRepository = sceneRepository;
    }

    @Transactional(readOnly = true)
    public BookDashboardResponse getDashboard(UUID bookId) {
        Book book = bookService.getBook(bookId);
        List<Scene> scenes = sceneRepository.findByBookIdOrderBySortOrderAsc(bookId);

        int totalScenes = scenes.size();
        int totalWordCount = scenes.stream()
                .mapToInt(this::wordCount)
                .sum();
        int plannedScenesCount = (int) scenes.stream()
                .filter(this::isPlanned)
                .count();

        return new BookDashboardResponse(
                book.getId(),
                book.getTitle(),
                totalWordCount,
                book.getTargetWordCount(),
                remainingWordCount(totalWordCount, book.getTargetWordCount()),
                wordCountProgressPercent(totalWordCount, book.getTargetWordCount()),
                exceededTargetWordCount(totalWordCount, book.getTargetWordCount()),
                sectionRepository.countByBookId(bookId),
                chapterRepository.countByBookId(bookId),
                totalScenes,
                new PlanningProgressResponse(plannedScenesCount, totalScenes, plannedScenesPercent(plannedScenesCount, totalScenes)),
                buildStatusCounts(scenes),
                buildPovStats(scenes),
                buildNarrativeGaps(scenes),
                buildCharacterUsage(scenes),
                buildLocationUsage(scenes),
                buildItemUsage(scenes)
        );
    }

    private List<StatusCountResponse> buildStatusCounts(List<Scene> scenes) {
        Map<SceneStatus, CountStats> statsByStatus = new EnumMap<>(SceneStatus.class);
        for (SceneStatus status : SceneStatus.values()) {
            statsByStatus.put(status, new CountStats());
        }

        for (Scene scene : scenes) {
            CountStats stats = statsByStatus.get(scene.getStatus());
            stats.add(scene);
        }

        return statsByStatus.entrySet()
                .stream()
                .map(entry -> new StatusCountResponse(
                        entry.getKey(),
                        entry.getValue().scenesCount,
                        entry.getValue().wordCount,
                        entry.getValue().scenes
                ))
                .toList();
    }

    private List<PovStatsResponse> buildPovStats(List<Scene> scenes) {
        Map<UUID, NamedCountStats> statsByCharacterId = new HashMap<>();

        for (Scene scene : scenes) {
            Character povCharacter = scene.getPovCharacter();
            if (povCharacter == null) {
                continue;
            }

            statsByCharacterId
                    .computeIfAbsent(povCharacter.getId(), id -> new NamedCountStats(id, povCharacter.getName()))
                    .add(wordCount(scene));
        }

        return statsByCharacterId.values()
                .stream()
                .sorted(namedStatsComparator())
                .map(stats -> new PovStatsResponse(stats.id, stats.name, stats.scenesCount, stats.wordCount))
                .toList();
    }

    private NarrativeGapsResponse buildNarrativeGaps(List<Scene> scenes) {
        int scenesWithoutPov = 0;
        int scenesWithoutGoal = 0;
        int scenesWithoutConflict = 0;
        int scenesWithoutOutcome = 0;
        int scenesWithoutMainLocation = 0;
        int scenesWithoutParticipants = 0;

        for (Scene scene : scenes) {
            if (scene.getPovCharacter() == null) {
                scenesWithoutPov++;
            }
            if (!hasText(scene.getGoal())) {
                scenesWithoutGoal++;
            }
            if (!hasText(scene.getConflict())) {
                scenesWithoutConflict++;
            }
            if (!hasText(scene.getOutcome())) {
                scenesWithoutOutcome++;
            }
            if (scene.getMainLocation() == null) {
                scenesWithoutMainLocation++;
            }
            if (scene.getParticipantCharacters().isEmpty()) {
                scenesWithoutParticipants++;
            }
        }

        return new NarrativeGapsResponse(
                scenesWithoutPov,
                scenesWithoutGoal,
                scenesWithoutConflict,
                scenesWithoutOutcome,
                scenesWithoutMainLocation,
                scenesWithoutParticipants
        );
    }

    private List<EntityUsageResponse> buildCharacterUsage(List<Scene> scenes) {
        Map<UUID, NamedCountStats> statsByCharacterId = new HashMap<>();

        for (Scene scene : scenes) {
            for (Character character : scene.getParticipantCharacters()) {
                statsByCharacterId
                        .computeIfAbsent(character.getId(), id -> new NamedCountStats(id, character.getName()))
                        .addScene();
            }
        }

        return toEntityUsage(statsByCharacterId);
    }

    private List<EntityUsageResponse> buildLocationUsage(List<Scene> scenes) {
        Map<UUID, NamedCountStats> statsByLocationId = new HashMap<>();

        for (Scene scene : scenes) {
            Location location = scene.getMainLocation();
            if (location == null) {
                continue;
            }

            statsByLocationId
                    .computeIfAbsent(location.getId(), id -> new NamedCountStats(id, location.getName()))
                    .addScene();
        }

        return toEntityUsage(statsByLocationId);
    }

    private List<EntityUsageResponse> buildItemUsage(List<Scene> scenes) {
        Map<UUID, NamedCountStats> statsByItemId = new HashMap<>();

        for (Scene scene : scenes) {
            for (Item item : scene.getItems()) {
                statsByItemId
                        .computeIfAbsent(item.getId(), id -> new NamedCountStats(id, item.getName()))
                        .addScene();
            }
        }

        return toEntityUsage(statsByItemId);
    }

    private List<EntityUsageResponse> toEntityUsage(Map<UUID, NamedCountStats> statsById) {
        return statsById.values()
                .stream()
                .sorted(namedStatsComparator())
                .map(stats -> new EntityUsageResponse(stats.id, stats.name, stats.scenesCount))
                .toList();
    }

    private Comparator<NamedCountStats> namedStatsComparator() {
        return Comparator
                .comparingInt((NamedCountStats stats) -> stats.scenesCount)
                .reversed()
                .thenComparing(stats -> stats.name)
                .thenComparing(stats -> stats.id);
    }

    private boolean isPlanned(Scene scene) {
        return scene.getPovCharacter() != null
                && hasText(scene.getGoal())
                && hasText(scene.getConflict())
                && hasText(scene.getOutcome());
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private int wordCount(Scene scene) {
        return scene.getWordCount() == null ? 0 : scene.getWordCount();
    }

    private DashboardSceneSummaryResponse toSceneSummary(Scene scene) {
        Chapter chapter = scene.getChapter();
        BookSection section = chapter.getSection();

        return new DashboardSceneSummaryResponse(
                scene.getId(),
                scene.getTitle(),
                scene.getSummary(),
                scene.getStatus(),
                wordCount(scene),
                chapter.getId(),
                chapter.getTitle(),
                section.getId(),
                section.getTitle(),
                scene.getPovCharacter() == null ? null : scene.getPovCharacter().getName(),
                scene.getMainLocation() == null ? null : scene.getMainLocation().getName(),
                scene.getParticipantCharacters()
                        .stream()
                        .map(Character::getName)
                        .sorted()
                        .toList(),
                scene.getItems()
                        .stream()
                        .map(Item::getName)
                        .sorted()
                        .toList(),
                scene.getGoal(),
                scene.getConflict(),
                scene.getOutcome(),
                scene.getPlanningNotes()
        );
    }

    private double plannedScenesPercent(int plannedScenesCount, int totalScenes) {
        if (totalScenes == 0) {
            return 0.0;
        }

        return (plannedScenesCount * 100.0) / totalScenes;
    }

    private Integer remainingWordCount(int totalWordCount, Integer targetWordCount) {
        if (!hasValidTargetWordCount(targetWordCount)) {
            return null;
        }

        return Math.max(targetWordCount - totalWordCount, 0);
    }

    private Double wordCountProgressPercent(int totalWordCount, Integer targetWordCount) {
        if (!hasValidTargetWordCount(targetWordCount)) {
            return null;
        }

        return (totalWordCount * 100.0) / targetWordCount;
    }

    private Integer exceededTargetWordCount(int totalWordCount, Integer targetWordCount) {
        if (!hasValidTargetWordCount(targetWordCount)) {
            return null;
        }

        return Math.max(totalWordCount - targetWordCount, 0);
    }

    private boolean hasValidTargetWordCount(Integer targetWordCount) {
        return targetWordCount != null && targetWordCount > 0;
    }

    private class CountStats {
        private int scenesCount;
        private int wordCount;
        private final List<DashboardSceneSummaryResponse> scenes = new java.util.ArrayList<>();

        void add(Scene scene) {
            scenesCount++;
            wordCount += wordCount(scene);
            scenes.add(toSceneSummary(scene));
        }
    }

    private static class NamedCountStats {
        private final UUID id;
        private final String name;
        private int scenesCount;
        private int wordCount;

        NamedCountStats(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        void add(int sceneWordCount) {
            scenesCount++;
            wordCount += sceneWordCount;
        }

        void addScene() {
            scenesCount++;
        }
    }
}
