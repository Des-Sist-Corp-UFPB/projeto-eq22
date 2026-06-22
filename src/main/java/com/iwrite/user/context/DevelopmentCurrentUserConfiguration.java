package com.iwrite.user.context;

import com.iwrite.common.timezone.IanaZoneIdValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DevelopmentCurrentUserProperties.class)
@ConditionalOnProperty(
        prefix = "iwrite.current-user.development",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class DevelopmentCurrentUserConfiguration {

    @Bean
    @ConditionalOnMissingBean(CurrentUserProvider.class)
    CurrentUserProvider developmentCurrentUserProvider(
            DevelopmentCurrentUserProperties properties,
            IanaZoneIdValidator validator
    ) {
        // TODO Replace this fallback with an authenticated provider when authentication is introduced.
        return new DevelopmentCurrentUserProvider(
                properties.getUserId(),
                properties.getTenantId(),
                validator.validate(properties.getTimeZoneId())
        );
    }
}
