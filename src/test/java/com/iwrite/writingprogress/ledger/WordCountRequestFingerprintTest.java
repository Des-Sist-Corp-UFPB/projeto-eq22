package com.iwrite.writingprogress.ledger;

import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.sceneversion.entity.SceneVersionSource;
import com.iwrite.writingprogress.ledger.service.WordCountRequestFingerprint;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WordCountRequestFingerprintTest {

    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID BOOK_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID CHAPTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID SCENE_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");

    @Test
    void sameCanonicalInputProducesStableSha256HexFingerprint() {
        String first = WordCountRequestFingerprint.sceneCreate(
                ACTOR_ID,
                BOOK_ID,
                CHAPTER_ID,
                "Title",
                null,
                SceneStatus.DRAFT,
                0,
                "{}",
                "content"
        );

        String second = WordCountRequestFingerprint.sceneCreate(
                ACTOR_ID,
                BOOK_ID,
                CHAPTER_ID,
                "Title",
                null,
                SceneStatus.DRAFT,
                0,
                "{}",
                "content"
        );

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void nullAndEmptyPayloadFieldsRemainDistinct() {
        String nullContent = WordCountRequestFingerprint.contentSave(
                ACTOR_ID,
                BOOK_ID,
                SCENE_ID,
                1L,
                SceneVersionSource.AUTO_SAVE,
                null,
                null
        );
        String emptyContent = WordCountRequestFingerprint.contentSave(
                ACTOR_ID,
                BOOK_ID,
                SCENE_ID,
                1L,
                SceneVersionSource.AUTO_SAVE,
                "",
                ""
        );

        assertThat(nullContent).isNotEqualTo(emptyContent);
    }

    @Test
    void operationTypeAndSourceVersionAffectFingerprint() {
        String autosave = WordCountRequestFingerprint.contentSave(
                ACTOR_ID,
                BOOK_ID,
                SCENE_ID,
                1L,
                SceneVersionSource.AUTO_SAVE,
                "{}",
                "content"
        );
        String manualSave = WordCountRequestFingerprint.contentSave(
                ACTOR_ID,
                BOOK_ID,
                SCENE_ID,
                1L,
                SceneVersionSource.MANUAL_SAVE,
                "{}",
                "content"
        );
        String restore = WordCountRequestFingerprint.versionRestore(
                ACTOR_ID,
                BOOK_ID,
                SCENE_ID,
                UUID.fromString("00000000-0000-0000-0000-000000000040"),
                1L
        );

        assertThat(autosave).isNotEqualTo(manualSave);
        assertThat(autosave).isNotEqualTo(restore);
    }
}
