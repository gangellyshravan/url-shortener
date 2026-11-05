package com.urlshortener.controller;

import com.urlshortener.contracts.CreateLinkRequest;
import com.urlshortener.contracts.CreateLinkResponse;
import com.urlshortener.contracts.LinkStats;
import com.urlshortener.service.LinkService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/links")
@SecurityRequirement(name = "BearerAuth")
public class LinksController {
    private final LinkService linkService;
    private final String publicBaseUrl;

    public LinksController(LinkService linkService, @Value("${PUBLIC_BASE_URL:}") String publicBaseUrl) {
        this.linkService = linkService;
        this.publicBaseUrl = publicBaseUrl;
    }

    @PostMapping
    public ResponseEntity<CreateLinkResponse> create(@Valid @RequestBody CreateLinkRequest request) {
        String code = linkService.create(request.url(), request.alias(), request.ttlSeconds());
        String baseUrl = publicBaseUrl.isBlank()
            ? ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString()
                : publicBaseUrl.replaceAll("/+$", "");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateLinkResponse(code, baseUrl + "/r/" + code));
    }

    @GetMapping("/{code}/stats")
    public LinkStats getStats(@PathVariable String code) {
        return linkService.getStats(code);
    }

    @DeleteMapping("/{code}")
    public ResponseEntity<Void> disable(@PathVariable String code) {
        linkService.disable(code);
        return ResponseEntity.noContent().build();
    }
}