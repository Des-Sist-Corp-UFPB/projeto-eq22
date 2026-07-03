package com.iwrite.book.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Token construction: 32 bytes from {@link SecureRandom} (256 bits of entropy),
 * encoded as unpadded URL-safe Base64 (43 characters), suitable for public
 * invitation links. Only the lowercase-hex SHA-256 digest of the raw token is
 * persisted; because the token itself is a full-entropy random value, an
 * unsalted deterministic digest is not brute-forceable and allows direct
 * indexed lookup by hash. No key, salt, or pepper is involved.
 */
@Service
public class InvitationTokenService {

    private static final int TOKEN_BYTE_LENGTH = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    public InvitationToken generate() {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);
        String rawValue = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new InvitationToken(rawValue, hash(rawValue));
    }

    public String hash(String rawToken) {
        return HexFormat.of().formatHex(sha256().digest(rawToken.getBytes(StandardCharsets.UTF_8)));
    }

    public boolean matches(String rawToken, String storedHash) {
        if (rawToken == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(rawToken).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8)
        );
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required but unavailable", exception);
        }
    }
}
