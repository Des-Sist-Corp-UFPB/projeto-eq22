package com.iwrite.writingprogress.service;

import com.iwrite.user.context.CurrentUserProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

@Service
public class WritingDayResolver {

    private final Clock clock;
    private final CurrentUserProvider currentUserProvider;

    public WritingDayResolver(Clock clock, CurrentUserProvider currentUserProvider) {
        this.clock = clock;
        this.currentUserProvider = currentUserProvider;
    }

    public LocalDate currentWritingDate() {
        return writingDateFor(clock.instant());
    }

    public LocalDate writingDateFor(Instant instant) {
        if (instant == null) {
            throw new IllegalArgumentException("instant is required");
        }
        return LocalDate.ofInstant(instant, currentUserProvider.effectiveZoneId());
    }
}
