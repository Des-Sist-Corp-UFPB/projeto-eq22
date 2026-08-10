package com.iwrite.common.timezone;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IanaZoneIdValidatorTest {

    private final IanaZoneIdValidator validator = new IanaZoneIdValidator();

    @ParameterizedTest
    @ValueSource(strings = {"America/Sao_Paulo", "Asia/Shanghai", "Europe/Lisbon", "UTC"})
    void acceptsIanaTimeZonesAndCanonicalUtc(String timeZoneId) {
        assertEquals(ZoneId.of(timeZoneId), validator.validate(timeZoneId));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "+03:00",
            "-03:00",
            "UTC-03:00",
            "UTC+03:00",
            "GMT+3",
            "Unknown/Nowhere",
            " America/Sao_Paulo "
    })
    void rejectsOffsetsUnknownZonesAndSurroundingWhitespace(String timeZoneId) {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(timeZoneId));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsNullOrBlankValues(String timeZoneId) {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(timeZoneId));
    }
}
