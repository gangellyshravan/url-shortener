package com.urlshortener.config;

import com.urlshortener.contracts.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

public class AdminRateLimitFilter extends OncePerRequestFilter {
    private static final long WINDOW_MILLIS = 60_000;
    private static final int LIMIT = 60;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public AdminRateLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/links");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String key = authentication == null ? request.getRemoteAddr() : "authenticated-admins";
        long now = Instant.now().toEpochMilli();
        windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= WINDOW_MILLIS);
        Window window = windows.computeIfAbsent(key, ignored -> new Window(now));
        synchronized (window) {
            if (now - window.startedAt >= WINDOW_MILLIS) {
                window.startedAt = now;
                window.count = 0;
            }
            if (window.count >= LIMIT) {
                response.setStatus(429);
                response.setContentType("application/json");
                objectMapper.writeValue(response.getOutputStream(), new ErrorResponse("Admin rate limit exceeded"));
                return;
            }
            window.count++;
        }
        chain.doFilter(request, response);
    }

    private static final class Window {
        private long startedAt;
        private int count;

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}