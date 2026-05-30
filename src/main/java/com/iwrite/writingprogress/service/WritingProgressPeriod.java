package com.iwrite.writingprogress.service;

import com.iwrite.common.exception.BadRequestException;

import java.time.LocalDate;

public enum WritingProgressPeriod {
    SEVEN_DAYS("7d") {
        @Override
        LocalDate startDate(LocalDate today) {
            return today.minusDays(6);
        }
    },
    FIFTEEN_DAYS("15d") {
        @Override
        LocalDate startDate(LocalDate today) {
            return today.minusDays(14);
        }
    },
    THIRTY_DAYS("30d") {
        @Override
        LocalDate startDate(LocalDate today) {
            return today.minusDays(29);
        }
    },
    THREE_MONTHS("3m") {
        @Override
        LocalDate startDate(LocalDate today) {
            return today.minusMonths(3);
        }
    },
    SIX_MONTHS("6m") {
        @Override
        LocalDate startDate(LocalDate today) {
            return today.minusMonths(6);
        }
    },
    TWELVE_MONTHS("12m") {
        @Override
        LocalDate startDate(LocalDate today) {
            return today.minusMonths(11).withDayOfMonth(1);
        }
    };

    public static final WritingProgressPeriod DEFAULT = SEVEN_DAYS;

    private final String requestValue;

    WritingProgressPeriod(String requestValue) {
        this.requestValue = requestValue;
    }

    public static WritingProgressPeriod fromRequestValue(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }

        for (WritingProgressPeriod period : values()) {
            if (period.requestValue.equals(value)) {
                return period;
            }
        }

        throw new BadRequestException("Unsupported progressPeriod: " + value);
    }

    public LocalDate startDateInclusive(LocalDate today) {
        return startDate(today);
    }

    abstract LocalDate startDate(LocalDate today);
}
