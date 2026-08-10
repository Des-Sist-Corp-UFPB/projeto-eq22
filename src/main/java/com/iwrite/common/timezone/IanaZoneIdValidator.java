package com.iwrite.common.timezone;

import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.ZoneId;

@Component
public class IanaZoneIdValidator {

    public ZoneId validate(String timeZoneId) {
        if (timeZoneId == null || timeZoneId.isBlank()) {
            throw invalid(timeZoneId);
        }
        if (!timeZoneId.equals(timeZoneId.trim())) {
            throw invalid(timeZoneId);
        }
        if (!timeZoneId.equals("UTC") && !timeZoneId.contains("/")) {
            throw invalid(timeZoneId);
        }
        if (timeZoneId.startsWith("+")
                || timeZoneId.startsWith("-")
                || timeZoneId.startsWith("UTC+")
                || timeZoneId.startsWith("UTC-")
                || timeZoneId.startsWith("GMT+")
                || timeZoneId.startsWith("GMT-")) {
            throw invalid(timeZoneId);
        }

        try {
            return ZoneId.of(timeZoneId);
        } catch (DateTimeException exception) {
            throw invalid(timeZoneId, exception);
        }
    }

    private IllegalArgumentException invalid(String timeZoneId) {
        return new IllegalArgumentException("Invalid IANA time zone ID: " + timeZoneId);
    }

    private IllegalArgumentException invalid(String timeZoneId, DateTimeException cause) {
        return new IllegalArgumentException("Invalid IANA time zone ID: " + timeZoneId, cause);
    }
}
