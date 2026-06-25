package com.iwrite.health.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
public class PingController {

    @GetMapping("/ping")
    public PingResponse ping() {
        return new PingResponse(
                "ok",
                "eq22",
                Instant.now().toString()
        );
    }

    public record PingResponse(
            String status,
            String service,
            String timestamp
    ) {
    }
}
