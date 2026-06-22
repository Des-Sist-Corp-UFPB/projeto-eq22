package com.iwrite.user.context;

import com.iwrite.common.timezone.IanaZoneIdValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DevelopmentCurrentUserConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DevelopmentCurrentUserConfiguration.class)
            .withBean(IanaZoneIdValidator.class);

    @Test
    void defaultContextDoesNotRegisterDevelopmentIdentity() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(CurrentUserProvider.class));
    }

    @Test
    void explicitDevelopmentConfigurationRegistersConfiguredIdentity() {
        contextRunner
                .withPropertyValues(
                        "iwrite.current-user.development.enabled=true",
                        "iwrite.current-user.development.user-id=10000000-0000-0000-0000-000000000001",
                        "iwrite.current-user.development.tenant-id=10000000-0000-0000-0000-000000000002",
                        "iwrite.current-user.development.time-zone-id=UTC"
                )
                .run(context -> {
                    CurrentUserProvider provider = context.getBean(CurrentUserProvider.class);
                    assertThat(provider).isInstanceOf(DevelopmentCurrentUserProvider.class);
                    assertThat(provider.userId()).isEqualTo(UUID.fromString("10000000-0000-0000-0000-000000000001"));
                    assertThat(provider.tenantId()).isEqualTo(UUID.fromString("10000000-0000-0000-0000-000000000002"));
                    assertThat(provider.effectiveZoneId()).isEqualTo(ZoneId.of("UTC"));
                });
    }

    @Test
    void anotherCurrentUserProviderSuppressesDevelopmentIdentity() {
        new ApplicationContextRunner()
                .withUserConfiguration(AuthenticatedProviderConfiguration.class, DevelopmentCurrentUserConfiguration.class)
                .withBean(IanaZoneIdValidator.class)
                .withPropertyValues("iwrite.current-user.development.enabled=true")
                .run(context -> assertThat(context.getBean(CurrentUserProvider.class))
                        .isSameAs(context.getBean("authenticatedCurrentUserProvider")));
    }

    @Test
    void invalidConfiguredUuidFailsContextStartup() {
        contextRunner
                .withPropertyValues(
                        "iwrite.current-user.development.enabled=true",
                        "iwrite.current-user.development.user-id=not-a-uuid"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void invalidConfiguredTimeZoneFailsContextStartup() {
        contextRunner
                .withPropertyValues(
                        "iwrite.current-user.development.enabled=true",
                        "iwrite.current-user.development.time-zone-id=GMT+03:00"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage("Invalid IANA time zone ID: GMT+03:00");
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AuthenticatedProviderConfiguration {

        @Bean
        CurrentUserProvider authenticatedCurrentUserProvider() {
            return new DevelopmentCurrentUserProvider(
                    UUID.fromString("20000000-0000-0000-0000-000000000001"),
                    UUID.fromString("20000000-0000-0000-0000-000000000002"),
                    ZoneId.of("UTC")
            );
        }
    }
}
