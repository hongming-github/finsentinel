package com.finsentinel.mcp.compliance;

import org.springaicommunity.mcp.security.server.config.McpServerOAuth2Configurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Callers need a token from the realm with this server as audience and the {@value #ROLE} role: in practice the
 * platform's service account. The tools still take the tenant as a parameter; keeping each agent to its own case is
 * the platform's job (ADR 0002), and this server only lets the platform in.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    static final String ROLE = "systems-reader";

    @Bean
    SecurityFilterChain security(HttpSecurity http, JwtDecoder jwtDecoder,
                                 @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter authentication = new JwtAuthenticationConverter();
        authentication.setJwtGrantedAuthoritiesConverter(roles);
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/mcp", "/mcp/**").hasRole(ROLE)
                        .anyRequest().denyAll())
                .with(McpServerOAuth2Configurer.mcpServerOAuth2(), mcp -> mcp
                        .authorizationServer(issuer)
                        .resourceName("FinSentinel watchlist screening")
                        .jwtDecoder(jwtDecoder)
                        .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(authentication))))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }
}
