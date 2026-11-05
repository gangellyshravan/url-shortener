package com.urlshortener.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter,
            @Value("${JWT_ADMIN_ROLE:urlshortener.admin}") String adminRole) throws Exception {
        String requiredAuthority = adminRole.startsWith("ROLE_") ? adminRole : "ROLE_" + adminRole;
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/health").permitAll()
                        .requestMatchers("/api/orchestration/**").hasAuthority(requiredAuthority)
                        .anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }

    @Bean
    Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter(
            @Value("${JWT_ROLE_CLAIM:role}") String roleClaim) {
        JwtGrantedAuthoritiesConverter scopeAuthorities = new JwtGrantedAuthoritiesConverter();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<org.springframework.security.core.GrantedAuthority> authorities =
                    new ArrayList<>(scopeAuthorities.convert(jwt));
            Object roles = jwt.getClaims().get(roleClaim);
            if (roles instanceof String role) {
                authorities.add(authority(role));
            } else if (roles instanceof Collection<?> roleValues) {
                roleValues.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .map(SecurityConfig::authority)
                        .forEach(authorities::add);
            }
            return authorities;
        });
        return converter::convert;
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${JWT_SECRET:}") String secret,
            @Value("${JWT_ISSUER_URI:}") String issuerUri,
            @Value("${JWT_JWKS_URI:}") String jwksUri) {
        if (!issuerUri.isBlank()) {
            return JwtDecoders.fromIssuerLocation(issuerUri);
        }
        if (!jwksUri.isBlank()) {
            return NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        }
        if (secret.isBlank()) {
            throw new IllegalStateException(
                    "Configure JWT_SECRET, JWT_ISSUER_URI, or JWT_JWKS_URI to enable JWT verification");
        }
        byte[] keyBytes;
        try {
            keyBytes = java.util.Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("JWT_SECRET must be a Base64-encoded key", exception);
        }
        if (keyBytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must decode to at least 32 bytes");
        }
        return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(keyBytes, "HmacSHA256"))
                .macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
                .build();
    }

    private static SimpleGrantedAuthority authority(String role) {
        return new SimpleGrantedAuthority(role.startsWith("ROLE_") ? role : "ROLE_" + role);
    }
}
