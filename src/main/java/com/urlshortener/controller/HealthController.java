package com.urlshortener.controller;

import com.urlshortener.contracts.ErrorResponse;
import com.urlshortener.service.LinkService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {
    private final LinkService linkService;

    public HealthController(LinkService linkService) {
        this.linkService = linkService;
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        if (!linkService.canConnect()) {
            return ResponseEntity.status(503).body(new ErrorResponse("Storage unavailable"));
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}