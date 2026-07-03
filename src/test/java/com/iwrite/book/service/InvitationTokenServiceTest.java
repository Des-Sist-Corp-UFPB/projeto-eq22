package com.iwrite.book.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class InvitationTokenServiceTest {

    private static final Pattern URL_SAFE_TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern LOWERCASE_HEX_64 = Pattern.compile("^[0-9a-f]{64}$");

    private final InvitationTokenService tokenService = new InvitationTokenService();

    @Test
    void generatesUrlSafeTokensWith256BitsOfEntropy() {
        InvitationToken token = tokenService.generate();

        // Boolean assertions keep raw token values out of failure output.
        assertThat(URL_SAFE_TOKEN.matcher(token.rawValue()).matches()).isTrue();
        assertThat(Base64.getUrlDecoder().decode(token.rawValue())).hasSize(32);
        assertThat(LOWERCASE_HEX_64.matcher(token.hashValue()).matches()).isTrue();
    }

    @Test
    void generatesDistinctTokensAndHashes() {
        Set<String> rawValues = new HashSet<>();
        Set<String> hashValues = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            InvitationToken token = tokenService.generate();
            rawValues.add(token.rawValue());
            hashValues.add(token.hashValue());
        }

        assertThat(rawValues).hasSize(200);
        assertThat(hashValues).hasSize(200);
    }

    @Test
    void hashIsDeterministicSha256Hex() throws Exception {
        String sample = "sample-invitation-token";
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(sample.getBytes(StandardCharsets.UTF_8))
        );

        assertThat(tokenService.hash(sample)).isEqualTo(expected);
        assertThat(tokenService.hash(sample)).isEqualTo(tokenService.hash(sample));
    }

    @Test
    void matchesAcceptsOnlyTheOriginalToken() {
        InvitationToken token = tokenService.generate();

        assertThat(tokenService.matches(token.rawValue(), token.hashValue())).isTrue();
        assertThat(tokenService.matches(token.rawValue() + "x", token.hashValue())).isFalse();
        assertThat(tokenService.matches("different-token", token.hashValue())).isFalse();
        assertThat(tokenService.matches(null, token.hashValue())).isFalse();
        assertThat(tokenService.matches(token.rawValue(), null)).isFalse();
    }

    @Test
    void tokenToStringIsRedacted() {
        InvitationToken token = tokenService.generate();

        assertThat(token.toString()).doesNotContain(token.rawValue());
        assertThat(token.toString()).doesNotContain(token.hashValue());
    }
}
