package com.iwrite.writingprogress.service;

import com.iwrite.book.dto.BookRequest;
import com.iwrite.book.dto.BookUpdateRequest;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.SwitchableCurrentUserProvider;
import com.iwrite.writingprogress.entity.BookWritingSchedule;
import com.iwrite.writingprogress.repository.BookWritingScheduleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_TENANT_ID;
import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(WritingScheduleIntegrationTest.CurrentUserTestConfiguration.class)
class WritingScheduleIntegrationTest extends PostgresIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 30);
    private static final Instant DEFAULT_INSTANT = Instant.parse("2026-05-30T12:00:00Z");

    @Autowired
    private BookWritingScheduleRepository scheduleRepository;

    @Autowired
    private SwitchableCurrentUserProvider currentUserProvider;

    @Autowired
    private MutableClock writingScheduleClock;

    @AfterEach
    void resetCurrentUserAndClock() {
        currentUserProvider.reset();
        writingScheduleClock.reset();
    }

    @Test
    void newBooksDefaultToEveryDaySchedule() {
        var book = createBook("default schedule");

        assertThat(book.plannedWritingDays()).containsExactly(DayOfWeek.values());
        var activeSchedule = scheduleRepository.findFirstByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id()).orElseThrow();
        assertThat(activeSchedule.getEffectiveFrom()).isEqualTo(TODAY);
        assertThat(activeSchedule.getEffectiveTo()).isNull();
        assertThat(activeSchedule.getPlannedDays()).containsExactlyInAnyOrder(DayOfWeek.values());
        assertScheduleTimestamps(activeSchedule, DEFAULT_INSTANT);
        assertThat(scheduleRepository.countByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id())).isEqualTo(1);
    }

    @Test
    void newBooksCanStartWithCustomSchedule() {
        var book = bookService.create(new BookRequest(
                "custom schedule",
                null,
                null,
                null,
                null,
                null,
                List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
        ));

        assertThat(book.plannedWritingDays()).containsExactly(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY);
        var activeSchedule = scheduleRepository.findFirstByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id()).orElseThrow();
        assertThat(activeSchedule.getPlannedDays()).containsExactlyInAnyOrder(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY);
    }

    @Test
    void emptyScheduleIsRejected() {
        assertThatThrownBy(() -> bookService.create(new BookRequest(
                "empty schedule",
                null,
                null,
                null,
                null,
                null,
                List.of()
        ))).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("plannedWritingDays");
    }

    @Test
    void updatingScheduleClosesCurrentVersionAndCreatesTomorrowVersion() {
        var book = createBook("change schedule");
        writingScheduleClock.setInstant(DEFAULT_INSTANT);
        BookUpdateRequest request = new BookUpdateRequest();
        request.setPlannedWritingDays(List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY));

        var updatedBook = bookService.update(book.id(), request);

        assertThat(updatedBook.plannedWritingDays())
                .containsExactly(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
        var schedules = scheduleRepository.findByUserIdAndBookIdOverlappingPeriod(DEFAULT_USER_ID, book.id(), TODAY.minusDays(1), TODAY.plusDays(2));
        assertThat(schedules).hasSize(2);
        assertThat(schedules.get(0).getEffectiveFrom()).isEqualTo(TODAY);
        assertThat(schedules.get(0).getEffectiveTo()).isEqualTo(TODAY.plusDays(1));
        assertThat(schedules.get(1).getEffectiveFrom()).isEqualTo(TODAY.plusDays(1));
        assertThat(schedules.get(1).getEffectiveTo()).isNull();
        assertThat(scheduleRepository.countByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id())).isEqualTo(1);
    }

    @Test
    void repeatedFutureScheduleEditReplacesPendingActiveVersion() {
        var book = createBook("replace pending schedule");
        BookUpdateRequest weekdaysRequest = new BookUpdateRequest();
        weekdaysRequest.setPlannedWritingDays(List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY));
        writingScheduleClock.setInstant(DEFAULT_INSTANT);
        bookService.update(book.id(), weekdaysRequest);

        BookUpdateRequest mondayRequest = new BookUpdateRequest();
        mondayRequest.setPlannedWritingDays(List.of(DayOfWeek.MONDAY));
        writingScheduleClock.setInstant(DEFAULT_INSTANT);
        bookService.update(book.id(), mondayRequest);

        var schedules = scheduleRepository.findByUserIdAndBookIdOverlappingPeriod(DEFAULT_USER_ID, book.id(), TODAY.minusDays(1), TODAY.plusDays(2));
        assertThat(schedules).hasSize(2);
        assertThat(schedules.get(1).getEffectiveFrom()).isEqualTo(TODAY.plusDays(1));
        assertThat(schedules.get(1).getPlannedDays()).containsExactly(DayOfWeek.MONDAY);
        assertThat(scheduleRepository.countByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id())).isEqualTo(1);
    }

    @Test
    void unchangedScheduleDoesNotCreateNewVersion() {
        var book = createBook("unchanged schedule");
        BookUpdateRequest request = new BookUpdateRequest();
        request.setPlannedWritingDays(List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));

        writingScheduleClock.setInstant(DEFAULT_INSTANT);
        bookService.update(book.id(), request);

        var schedules = scheduleRepository.findByUserIdAndBookIdOverlappingPeriod(DEFAULT_USER_ID, book.id(), TODAY.minusDays(1), TODAY.plusDays(2));
        assertThat(schedules).hasSize(1);
        assertThat(scheduleRepository.countByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id())).isEqualTo(1);
    }

    @Test
    void scheduleCreationAndChangeUseCurrentUsersLocalDateNearUtcMidnight() {
        Instant creationInstant = Instant.parse("2026-05-30T00:30:00Z");
        writingScheduleClock.setInstant(creationInstant);
        currentUserProvider.switchTo(DEFAULT_USER_ID, DEFAULT_TENANT_ID, ZoneId.of("America/Los_Angeles"));
        LocalDate localToday = LocalDate.of(2026, 5, 29);

        var book = createBook("timezone schedule");
        assertThat(writingScheduleClock.readCount()).isEqualTo(1);

        Instant changeInstant = Instant.parse("2026-05-30T01:30:00Z");
        writingScheduleClock.setInstant(changeInstant);
        BookUpdateRequest request = new BookUpdateRequest();
        request.setPlannedWritingDays(List.of(DayOfWeek.MONDAY));

        var updatedBook = bookService.update(book.id(), request);

        assertThat(writingScheduleClock.readCount()).isEqualTo(1);
        assertThat(updatedBook.plannedWritingDays()).containsExactly(DayOfWeek.MONDAY);
        var schedules = scheduleRepository.findByUserIdAndBookIdOverlappingPeriod(
                DEFAULT_USER_ID,
                book.id(),
                localToday.minusDays(1),
                localToday.plusDays(2)
        );
        assertThat(schedules).hasSize(2);
        assertThat(schedules.get(0).getEffectiveFrom()).isEqualTo(localToday);
        assertThat(schedules.get(0).getEffectiveTo()).isEqualTo(localToday.plusDays(1));
        assertThat(schedules.get(0).getCreatedAt().toInstant()).isEqualTo(creationInstant);
        assertThat(schedules.get(0).getUpdatedAt().toInstant()).isEqualTo(changeInstant);
        assertThat(schedules.get(1).getEffectiveFrom()).isEqualTo(localToday.plusDays(1));
        assertThat(schedules.get(1).getEffectiveTo()).isNull();
        assertThat(schedules.get(1).getPlannedDays()).containsExactly(DayOfWeek.MONDAY);
        assertScheduleTimestamps(schedules.get(1), changeInstant);
        assertThat(scheduleRepository.countByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id())).isEqualTo(1);
    }

    @Test
    void scheduleTimestampsPersistMicrosecondPrecisionAfterReload() {
        Instant microsecondInstant = Instant.parse("2026-06-24T01:23:45.123456Z");
        writingScheduleClock.setInstant(microsecondInstant);

        var book = createBook("microsecond schedule timestamp");

        var activeSchedule = scheduleRepository.findFirstByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id()).orElseThrow();
        assertScheduleTimestamps(activeSchedule, microsecondInstant);
    }

    @Test
    void initialScheduleUsesOneInstantForTimestampAndUserLocalEffectiveDate() {
        Instant beforeLocalMidnight = Instant.parse("2026-05-30T02:30:00Z");
        Instant afterLocalMidnight = Instant.parse("2026-05-30T03:30:00Z");
        writingScheduleClock.setInstants(beforeLocalMidnight, afterLocalMidnight);
        currentUserProvider.switchTo(DEFAULT_USER_ID, DEFAULT_TENANT_ID, ZoneId.of("America/Sao_Paulo"));

        var book = createBook("single instant initial schedule");

        var activeSchedule = scheduleRepository.findFirstByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id()).orElseThrow();
        assertThat(activeSchedule.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 5, 29));
        assertScheduleTimestamps(activeSchedule, beforeLocalMidnight);
        assertThat(writingScheduleClock.readCount()).isEqualTo(1);
    }

    @Test
    void lazyScheduleCreationUsesOneInstantForTimestampAndUserLocalEffectiveDate() {
        var book = createBook("single instant lazy schedule");
        var initialSchedule = scheduleRepository.findFirstByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id()).orElseThrow();
        scheduleRepository.delete(initialSchedule);
        scheduleRepository.flush();
        writingScheduleClock.resetReadCount();

        Instant beforeLocalMidnight = Instant.parse("2026-05-30T02:30:00Z");
        Instant afterLocalMidnight = Instant.parse("2026-05-30T03:30:00Z");
        writingScheduleClock.setInstants(beforeLocalMidnight, afterLocalMidnight);
        currentUserProvider.switchTo(DEFAULT_USER_ID, DEFAULT_TENANT_ID, ZoneId.of("America/Sao_Paulo"));

        bookService.findById(book.id());

        var activeSchedule = scheduleRepository.findFirstByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id()).orElseThrow();
        assertThat(activeSchedule.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 5, 29));
        assertScheduleTimestamps(activeSchedule, beforeLocalMidnight);
        assertThat(writingScheduleClock.readCount()).isEqualTo(1);
    }

    @Test
    void updatedAtChangesOnLaterScheduleModificationWithoutChangingCreatedAt() {
        var book = createBook("later schedule timestamp");
        Instant creationInstant = DEFAULT_INSTANT;
        Instant updateInstant = Instant.parse("2026-05-31T12:00:00Z");
        writingScheduleClock.setInstant(updateInstant);
        BookUpdateRequest request = new BookUpdateRequest();
        request.setPlannedWritingDays(List.of(DayOfWeek.MONDAY));

        bookService.update(book.id(), request);

        var schedules = scheduleRepository.findByUserIdAndBookIdOverlappingPeriod(
                DEFAULT_USER_ID,
                book.id(),
                TODAY.minusDays(1),
                TODAY.plusDays(3)
        );
        assertThat(schedules).hasSize(2);
        assertThat(schedules.get(0).getCreatedAt().toInstant()).isEqualTo(creationInstant);
        assertThat(schedules.get(0).getUpdatedAt().toInstant()).isEqualTo(updateInstant);
        assertScheduleTimestamps(schedules.get(1), updateInstant);
    }

    private void assertScheduleTimestamps(BookWritingSchedule schedule, Instant expectedInstant) {
        OffsetDateTime expectedTimestamp = expectedInstant.atOffset(ZoneOffset.UTC);
        assertThat(schedule.getCreatedAt()).isEqualTo(expectedTimestamp);
        assertThat(schedule.getUpdatedAt()).isEqualTo(expectedTimestamp);
        assertThat(schedule.getCreatedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(schedule.getUpdatedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    @TestConfiguration
    static class MutableWritingScheduleClockConfig {

        @Bean
        @Primary
        MutableClock mutableWritingScheduleClock() {
            return new MutableClock(DEFAULT_INSTANT, ZoneOffset.UTC);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CurrentUserTestConfiguration {

        @Bean
        @Primary
        SwitchableCurrentUserProvider switchableCurrentUserProvider() {
            return new SwitchableCurrentUserProvider();
        }
    }

    static class MutableClock extends Clock {

        private final ZoneId zone;
        private final List<Instant> instants = new ArrayList<>();
        private int readCount;

        MutableClock(Instant instant, ZoneId zone) {
            this.zone = zone;
            setInstant(instant);
        }

        void setInstant(Instant instant) {
            setInstants(instant);
        }

        void setInstants(Instant firstInstant, Instant... additionalInstants) {
            instants.clear();
            instants.add(firstInstant);
            instants.addAll(Arrays.asList(additionalInstants));
            readCount = 0;
        }

        void reset() {
            setInstant(DEFAULT_INSTANT);
        }

        void resetReadCount() {
            readCount = 0;
        }

        int readCount() {
            return readCount;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return zone;
                }

                @Override
                public Clock withZone(ZoneId newZone) {
                    return MutableClock.this.withZone(newZone);
                }

                @Override
                public Instant instant() {
                    return MutableClock.this.instant();
                }
            };
        }

        @Override
        public Instant instant() {
            if (readCount >= instants.size()) {
                throw new AssertionError("Unexpected clock read #" + (readCount + 1)
                        + "; only " + instants.size() + " instant(s) supplied");
            }
            return instants.get(readCount++);
        }
    }
}
