package com.iwrite.auth;

import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.user.context.AuthenticatedCurrentUserProvider;
import com.iwrite.user.context.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The other half of the mutual exclusion. {@code PostgresIntegrationTest} enables the development
 * identity, so this context must not contain the authenticated provider — the two are chosen by
 * one configuration switch rather than by {@code @Primary} or by bean ordering, and the safe
 * direction is that turning the development identity on cannot leave both wired.
 *
 * <p>The complementary case (development off) is asserted in
 * {@code AuthenticatedTenantResolutionIntegrationTest}.
 */
class DevelopmentIdentityWiringIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void authenticatedProviderIsAbsentWhileTheDevelopmentIdentityIsEnabled() {
        assertThat(applicationContext.getBeanNamesForType(AuthenticatedCurrentUserProvider.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(CurrentUserProvider.class)).isNotEmpty();
    }
}
