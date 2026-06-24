package com.iwrite.writingprogress.ledger.service;

import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.sceneversion.entity.SceneVersionSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public final class WordCountRequestFingerprint {

    private WordCountRequestFingerprint() {
    }

    public static String sceneCreate(
            UUID actorUserId,
            UUID bookId,
            UUID chapterId,
            String title,
            String summary,
            SceneStatus status,
            Integer sortOrder,
            String contentJson,
            String contentText
    ) {
        return digest(
                "SCENE_CREATE",
                actorUserId,
                bookId,
                chapterId,
                title,
                summary,
                status == null ? null : status.name(),
                sortOrder,
                contentJson,
                contentText
        );
    }

    public static String contentSave(
            UUID actorUserId,
            UUID bookId,
            UUID sceneId,
            Long expectedContentRevision,
            SceneVersionSource source,
            String contentJson,
            String contentText
    ) {
        return digest(
                "CONTENT_SAVE",
                actorUserId,
                bookId,
                sceneId,
                expectedContentRevision,
                source == null ? null : source.name(),
                contentJson,
                contentText
        );
    }

    public static String versionRestore(
            UUID actorUserId,
            UUID bookId,
            UUID sceneId,
            UUID versionId,
            Long expectedContentRevision
    ) {
        return digest(
                "VERSION_RESTORE",
                actorUserId,
                bookId,
                sceneId,
                versionId,
                expectedContentRevision
        );
    }

    public static String sceneDelete(
            UUID actorUserId,
            UUID bookId,
            UUID originalSceneId,
            UUID operationId,
            UUID idempotencyKey,
            Long contentRevisionBefore
    ) {
        return digest(
                "SCENE_DELETE",
                actorUserId,
                bookId,
                originalSceneId,
                operationId,
                idempotencyKey,
                contentRevisionBefore
        );
    }

    private static String digest(Object... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(canonical(fields).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String canonical(Object... fields) {
        StringBuilder builder = new StringBuilder();
        for (Object field : fields) {
            if (field == null) {
                builder.append("-1:");
            } else {
                String value = field.toString();
                builder.append(value.length()).append(':').append(value);
            }
            builder.append(';');
        }
        return builder.toString();
    }
}
