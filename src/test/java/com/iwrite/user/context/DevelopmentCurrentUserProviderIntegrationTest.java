package com.iwrite.user.context;

import com.iwrite.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DevelopmentCurrentUserProviderIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private CurrentUserProvider currentUserProvider;

    @Test
    void contextProvidesConfiguredDevelopmentIdentityAndTimeZone() {
        assertInstanceOf(DevelopmentCurrentUserProvider.class, currentUserProvider);
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000002"), currentUserProvider.userId());
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000001"), currentUserProvider.tenantId());
        assertEquals(ZoneId.of("America/Sao_Paulo"), currentUserProvider.effectiveZoneId());
    }

    @Test
    void newlyCreatedBooksAreAnnotatedWithTheConfiguredTenant() {
        UUID bookId = createBook("Tenant annotation").id();
        assertEquals(
                currentUserProvider.tenantId(),
                bookService.getBook(bookId).getTenant().getId()
        );
    }
}
