package com.iwrite.writingprogress.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class WritingProgressClockConfig {

    @Bean
    public Clock writingProgressClock() {
        return Clock.systemDefaultZone();
    }
}
