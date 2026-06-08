package com.iwrite.sceneversion.service;

import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.scene.entity.Scene;
import com.iwrite.scene.repository.SceneRepository;
import com.iwrite.sceneversion.dto.SceneVersionDetailResponse;
import com.iwrite.sceneversion.dto.SceneVersionPageResponse;
import com.iwrite.sceneversion.dto.SceneVersionSummaryResponse;
import com.iwrite.sceneversion.entity.SceneVersion;
import com.iwrite.sceneversion.entity.SceneVersionSource;
import com.iwrite.sceneversion.repository.SceneVersionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class SceneVersionService {

    private static final Duration AUTOSAVE_CHECKPOINT_INTERVAL = Duration.ofMinutes(5);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String UNTITLED_SCENE_TITLE = "Cena sem titulo";

    private final SceneVersionRepository versionRepository;
    private final SceneRepository sceneRepository;

    public SceneVersionService(SceneVersionRepository versionRepository, SceneRepository sceneRepository) {
        this.versionRepository = versionRepository;
        this.sceneRepository = sceneRepository;
    }

    @Transactional(readOnly = true)
    public SceneVersionPageResponse listVersions(UUID sceneId, int page, int size) {
        requireCurrentScene(sceneId);
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size <= 0 ? DEFAULT_PAGE_SIZE : size, MAX_PAGE_SIZE));
        var versions = versionRepository.findBySceneIdOrderByCreatedAtDesc(
                sceneId,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"))
        );

        List<SceneVersionSummaryResponse> items = versions.getContent()
                .stream()
                .map(SceneVersionSummaryResponse::fromEntity)
                .toList();

        return new SceneVersionPageResponse(items, safePage, safeSize, versions.hasNext());
    }

    @Transactional(readOnly = true)
    public SceneVersionDetailResponse findVersion(UUID sceneId, UUID versionId) {
        requireCurrentScene(sceneId);
        return SceneVersionDetailResponse.fromEntity(getCurrentSceneVersion(sceneId, versionId));
    }

    @Transactional(readOnly = true)
    public SceneVersion getCurrentSceneVersion(UUID sceneId, UUID versionId) {
        return versionRepository.findByIdAndSceneId(versionId, sceneId)
                .orElseThrow(() -> new ResourceNotFoundException("Scene version not found: " + versionId));
    }

    public void checkpointBeforeContentOverwrite(Scene scene, SceneVersionSource source) {
        if (source == SceneVersionSource.AUTO_SAVE && isAutosaveThrottled(scene)) {
            return;
        }

        createSnapshotIfRecoverable(scene, source);
    }

    public void checkpointBeforeRestore(Scene scene) {
        createSnapshotIfRecoverable(scene, SceneVersionSource.RESTORE_SAFETY);
    }

    public void checkpointBeforeDelete(Scene scene) {
        createSnapshotIfRecoverable(scene, SceneVersionSource.DELETE_SAFETY);
    }

    public void checkpointBeforeDelete(List<Scene> scenes) {
        scenes.forEach(this::checkpointBeforeDelete);
    }

    public String hash(String contentJson, String contentText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(normalize(contentJson).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(normalize(contentText).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void createSnapshotIfRecoverable(Scene scene, SceneVersionSource source) {
        if (isEmptyContent(scene.getContentJson(), scene.getContentText())) {
            return;
        }

        String contentHash = hash(scene.getContentJson(), scene.getContentText());
        boolean latestHasSameHash = versionRepository.findTopBySceneIdOrderByCreatedAtDesc(scene.getId())
                .map(SceneVersion::getContentHash)
                .filter(contentHash::equals)
                .isPresent();
        if (latestHasSameHash) {
            return;
        }

        SceneVersion version = new SceneVersion();
        version.setBook(scene.getBook());
        version.setScene(scene);
        version.setOriginalSceneId(scene.getId());
        version.setSceneTitleSnapshot(snapshotTitle(scene.getTitle()));
        version.setContentJson(scene.getContentJson());
        version.setContentText(scene.getContentText());
        version.setWordCount(scene.getWordCount() == null ? 0 : scene.getWordCount());
        version.setSource(source);
        version.setContentHash(contentHash);
        versionRepository.save(version);
    }

    private boolean isAutosaveThrottled(Scene scene) {
        return versionRepository.findTopBySceneIdAndSourceOrderByCreatedAtDesc(scene.getId(), SceneVersionSource.AUTO_SAVE)
                .map(SceneVersion::getCreatedAt)
                .map(createdAt -> createdAt.plus(AUTOSAVE_CHECKPOINT_INTERVAL).isAfter(OffsetDateTime.now()))
                .orElse(false);
    }

    private void requireCurrentScene(UUID sceneId) {
        if (!sceneRepository.existsById(sceneId)) {
            throw new ResourceNotFoundException("Scene not found: " + sceneId);
        }
    }

    private boolean isEmptyContent(String contentJson, String contentText) {
        return normalize(contentJson).isBlank() && normalize(contentText).isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value;
    }

    private String snapshotTitle(String title) {
        if (title == null || title.isBlank()) {
            return UNTITLED_SCENE_TITLE;
        }

        return title.trim();
    }
}
