package com.iwrite.writingprogress.service;

import com.iwrite.book.dto.BookRequest;
import com.iwrite.book.dto.BookUpdateRequest;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.writingprogress.repository.BookWritingScheduleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_USER_ID;

class WritingScheduleIntegrationTest extends PostgresIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 30);

    @Autowired
    private BookWritingScheduleRepository scheduleRepository;

    @Test
    void newBooksDefaultToEveryDaySchedule() {
        var book = createBook("default schedule");

        assertThat(book.plannedWritingDays()).containsExactly(DayOfWeek.values());
        var activeSchedule = scheduleRepository.findFirstByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id()).orElseThrow();
        assertThat(activeSchedule.getEffectiveFrom()).isEqualTo(TODAY);
        assertThat(activeSchedule.getEffectiveTo()).isNull();
        assertThat(activeSchedule.getPlannedDays()).containsExactlyInAnyOrder(DayOfWeek.values());
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
        bookService.update(book.id(), weekdaysRequest);

        BookUpdateRequest mondayRequest = new BookUpdateRequest();
        mondayRequest.setPlannedWritingDays(List.of(DayOfWeek.MONDAY));
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

        bookService.update(book.id(), request);

        var schedules = scheduleRepository.findByUserIdAndBookIdOverlappingPeriod(DEFAULT_USER_ID, book.id(), TODAY.minusDays(1), TODAY.plusDays(2));
        assertThat(schedules).hasSize(1);
        assertThat(scheduleRepository.countByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id())).isEqualTo(1);
    }

    @TestConfiguration
    static class FixedWritingScheduleClockConfig {

        @Bean
        @Primary
        Clock fixedWritingScheduleClock() {
            return Clock.fixed(Instant.parse("2026-05-30T12:00:00Z"), ZoneOffset.UTC);
        }
    }
}
