package com.iwrite.sceneversion.repository;

import com.iwrite.sceneversion.entity.SceneVersion;
import com.iwrite.sceneversion.entity.SceneVersionSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface SceneVersionRepository extends JpaRepository<SceneVersion, UUID> {

    Page<SceneVersion> findBySceneIdOrderByCreatedAtDesc(UUID sceneId, Pageable pageable);

    Optional<SceneVersion> findByIdAndSceneId(UUID versionId, UUID sceneId);

    Optional<SceneVersion> findTopBySceneIdOrderByCreatedAtDesc(UUID sceneId);

    Optional<SceneVersion> findTopBySceneIdAndSourceOrderByCreatedAtDesc(UUID sceneId, SceneVersionSource source);

    List<SceneVersion> findByOriginalSceneIdOrderByCreatedAtDesc(UUID originalSceneId);

    long countByOriginalSceneId(UUID originalSceneId);
}
