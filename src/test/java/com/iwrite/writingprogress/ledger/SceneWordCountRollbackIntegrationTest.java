package com.iwrite.writingprogress.ledger;

import com.iwrite.scene.dto.SceneContentRequest;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.sceneversion.entity.SceneVersionSource;
import com.iwrite.sceneversion.repository.SceneVersionRepository;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.writingprogress.ledger.repository.BookWordCountEventRepository;
import com.iwrite.writingprogress.repository.DailyWritingProgressRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;

@SuppressWarnings("removal")
class SceneWordCountRollbackIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private BookWordCountEventRepository eventRepository;

    @Autowired
    private SceneVersionRepository sceneVersionRepository;

    @SpyBean
    private DailyWritingProgressRepository progressRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollupFailureRollsBackSceneVersionEventAndProgress() {
        var world = createStoryWorld("b7c rollback");
        long eventsBefore = eventRepository.countByBookId(world.book().id());
        long versionsBefore = sceneVersionRepository.countByOriginalSceneId(world.scene().id());
        SceneResponse before = sceneService.findById(world.scene().id());

        doThrow(new IllegalStateException("forced rollup failure"))
                .when(progressRepository)
                .upsertWordCountEventRollup(
                        any(UUID.class),
                        any(UUID.class),
                        any(UUID.class),
                        any(LocalDate.class),
                        any(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        any(OffsetDateTime.class)
                );

        assertThatThrownBy(() -> sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest(
                        "{}",
                        "rollback changed words",
                        SceneVersionSource.MANUAL_SAVE,
                        before.contentRevision(),
                        UUID.randomUUID()
                )
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forced rollup failure");

        entityManager.clear();
        SceneResponse after = sceneService.findById(world.scene().id());
        assertThat(after.contentText()).isEqualTo(before.contentText());
        assertThat(after.wordCount()).isEqualTo(before.wordCount());
        assertThat(after.contentRevision()).isEqualTo(before.contentRevision());
        assertThat(eventRepository.countByBookId(world.book().id())).isEqualTo(eventsBefore);
        assertThat(sceneVersionRepository.countByOriginalSceneId(world.scene().id())).isEqualTo(versionsBefore);
    }
}
