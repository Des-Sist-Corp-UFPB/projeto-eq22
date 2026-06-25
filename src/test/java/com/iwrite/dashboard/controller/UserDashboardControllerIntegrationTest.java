package com.iwrite.dashboard.controller;

import com.iwrite.book.entity.Book;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.entity.TenantMembershipRole;
import com.iwrite.user.entity.User;
import com.iwrite.writingprogress.entity.DailyWritingProgress;
import com.iwrite.writingprogress.repository.DailyWritingProgressRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_TENANT_ID;
import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_USER_ID;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(UserDashboardControllerIntegrationTest.FixedWritingProgressClockConfig.class)
class UserDashboardControllerIntegrationTest extends PostgresIntegrationTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-06-24T03:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 24);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DailyWritingProgressRepository progressRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void currentUserDashboardDefaultsToSevenDayPeriodAndReturnsZeroStateWhenEmpty() throws Exception {
        mockMvc.perform(get("/api/dashboard/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.value").value("7d"))
                .andExpect(jsonPath("$.period.startDate").value("2026-06-18"))
                .andExpect(jsonPath("$.period.endDate").value("2026-06-24"))
                .andExpect(jsonPath("$.summary.productiveWords").value(0))
                .andExpect(jsonPath("$.summary.manuscriptAdjustments").value(0))
                .andExpect(jsonPath("$.summary.writingDays").value(0))
                .andExpect(jsonPath("$.summary.booksWrittenIn").value(0))
                .andExpect(jsonPath("$.summary.currentGlobalWritingStreak").value(0))
                .andExpect(jsonPath("$.summary.bestGlobalWritingStreak").value(0))
                .andExpect(jsonPath("$.dailySeries", hasSize(7)))
                .andExpect(jsonPath("$.bookContributions", hasSize(0)));
    }

    @Test
    void currentUserDashboardAcceptsExplicitPeriodAndRespectsCurrentTenantAndUser() throws Exception {
        Book currentUserBook = bookService.getBook(createBook("HTTP user dashboard current").id());
        Book otherCurrentTenantBook = bookService.getBook(createBook("HTTP user dashboard other user").id());
        User otherUser = createUser("HTTP Other User", "http.dashboard.other@iwrite.local");
        addDefaultTenantMembership(otherUser);
        Book foreignTenantBook = createForeignTenantBook("HTTP user dashboard foreign");

        saveProgress(currentUserBook, DEFAULT_USER_ID, TODAY, 10, -1);
        saveProgress(otherCurrentTenantBook, otherUser.getId(), TODAY, 200, 0);
        saveProgress(foreignTenantBook, DEFAULT_USER_ID, TODAY, 300, 0);
        entityManager.flush();

        mockMvc.perform(get("/api/dashboard/me").param("progressPeriod", "30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.value").value("30d"))
                .andExpect(jsonPath("$.summary.productiveWords").value(10))
                .andExpect(jsonPath("$.summary.manuscriptAdjustments").value(-1))
                .andExpect(jsonPath("$.summary.writingDays").value(1))
                .andExpect(jsonPath("$.summary.booksWrittenIn").value(1))
                .andExpect(jsonPath("$.dailySeries[?(@.date == '2026-06-24')].productiveWords").value(hasItem(10)))
                .andExpect(jsonPath("$.bookContributions", hasSize(1)))
                .andExpect(jsonPath("$.bookContributions[0].bookId").value(currentUserBook.getId().toString()))
                .andExpect(jsonPath("$.bookContributions[0].title").value("HTTP user dashboard current"));
    }

    @Test
    void currentUserDashboardRejectsInvalidPeriod() throws Exception {
        mockMvc.perform(get("/api/dashboard/me").param("progressPeriod", "weekly"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("progressPeriod"))));
    }

    private void saveProgress(Book book, UUID userId, LocalDate progressDate, int productiveWords, int manuscriptAdjustments) {
        DailyWritingProgress progress = new DailyWritingProgress();
        progress.setBook(entityManager.getReference(Book.class, book.getId()));
        progress.setUser(entityManager.getReference(User.class, userId));
        progress.setProgressDate(progressDate);
        progress.setStartingManuscriptWordCount(0);
        progress.setEndingManuscriptWordCount(productiveWords + manuscriptAdjustments);
        progress.setProductiveWordCountChange(productiveWords);
        progress.setManuscriptAdjustmentWordCount(manuscriptAdjustments);
        progressRepository.save(progress);
    }

    private User createUser(String displayName, String email) {
        User user = new User();
        user.setDisplayName(displayName);
        user.setEmail(email);
        user.setTimeZoneId("America/Sao_Paulo");
        entityManager.persist(user);
        return user;
    }

    private void addDefaultTenantMembership(User user) {
        TenantMembership membership = new TenantMembership();
        membership.setTenant(entityManager.getReference(Tenant.class, DEFAULT_TENANT_ID));
        membership.setUser(user);
        membership.setRole(TenantMembershipRole.OWNER);
        entityManager.persist(membership);
    }

    private Book createForeignTenantBook(String title) {
        Tenant tenant = new Tenant();
        tenant.setName("HTTP user dashboard foreign tenant");
        tenant.setDefaultTimeZoneId("UTC");
        entityManager.persist(tenant);

        Book book = new Book();
        book.setTenant(tenant);
        book.setTitle(title);
        entityManager.persist(book);
        return book;
    }

    @TestConfiguration
    static class FixedWritingProgressClockConfig {

        @Bean
        @Primary
        Clock fixedWritingProgressClock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }
    }
}
