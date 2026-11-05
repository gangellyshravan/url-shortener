package com.urlshortener.controller;

import com.urlshortener.service.LinkService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
public class RedirectController {
    private final LinkService linkService;

    public RedirectController(LinkService linkService) {
        this.linkService = linkService;
    }

    @GetMapping("/r/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        String destination = normalizeDestination(linkService.resolve(code));
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, destination)
                .build();
    }

    private static String normalizeDestination(String destination) {
        URI uri = URI.create(destination);
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
            return destination;
        }
        var normalized = new StringBuilder(uri.getScheme()).append("://")
                .append(uri.getRawAuthority()).append('/');
        if (uri.getRawQuery() != null) {
            normalized.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            normalized.append('#').append(uri.getRawFragment());
        }
        return normalized.toString();
    }
}