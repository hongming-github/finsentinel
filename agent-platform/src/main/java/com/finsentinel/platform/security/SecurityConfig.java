package com.finsentinel.platform.security;

import com.finsentinel.platform.audit.AuditFilter;
import com.finsentinel.platform.audit.AuditLog;
import org.springaicommunity.mcp.security.server.config.McpServerOAuth2Configurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Who may call what (ADR 0014). Every request needs a JWT from the realm, with this platform as audience
 * ({@code spring.security.oauth2.resourceserver.jwt}); roles come from the {@value Caller#ROLES_CLAIM} claim and the
 * tenant from {@value Caller#TENANT_CLAIM}. Both chains deny anything not listed, so a new endpoint is closed until
 * someone opens it on purpose.
 *
 * <ul>
 *   <li>analyst: reads its bank's cases and alerts, decides cases</li>
 *   <li>supervisor: reads its bank's cases and alerts, the LLM cost report and the audit log; does not decide</li>
 *   <li>assistant: an analyst's AI assistant, MCP only</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    static final String ANALYST = "analyst";
    static final String SUPERVISOR = "supervisor";
    static final String ASSISTANT = "assistant";

    /**
     * The MCP endpoint, secured as the MCP authorization spec asks: a 401 names the protected resource metadata
     * ({@code /.well-known/oauth-protected-resource/mcp}), which names the authorization server.
     */
    @Bean
    @Order(1)
    SecurityFilterChain mcpSecurity(HttpSecurity http, JwtDecoder jwtDecoder, AuditLog audit,
                                    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        return http
                .securityMatcher("/mcp", "/mcp/**", "/.well-known/oauth-protected-resource/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/mcp", "/mcp/**").hasAnyRole(ANALYST, SUPERVISOR, ASSISTANT)
                        .anyRequest().denyAll())
                .with(McpServerOAuth2Configurer.mcpServerOAuth2(), mcp -> mcp
                        .authorizationServer(issuer)
                        .resourceName("FinSentinel cases and MAS regulations")
                        .jwtDecoder(jwtDecoder)
                        .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(authentication()))))
                .addFilterAfter(new TenancyFilter(), AuthorizationFilter.class)
                .addFilterBefore(new AuditFilter(audit), BearerTokenAuthenticationFilter.class)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiSecurity(HttpSecurity http, AuditLog audit) {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole(SUPERVISOR)
                        .requestMatchers(HttpMethod.POST, "/api/tenants/*/cases/*/decision").hasRole(ANALYST)
                        .requestMatchers(HttpMethod.GET, "/api/tenants/*/llm-usage", "/api/tenants/*/audit",
                                "/api/tenants/*/audit/verify").hasRole(SUPERVISOR)
                        .requestMatchers(HttpMethod.GET, "/api/tenants/*/cases", "/api/tenants/*/cases/*",
                                "/api/tenants/*/alerts").hasAnyRole(ANALYST, SUPERVISOR)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(authentication())))
                .addFilterAfter(new TenantPathFilter(), AuthorizationFilter.class)
                .addFilterAfter(new TenancyFilter(), TenantPathFilter.class)
                .addFilterBefore(new AuditFilter(audit), BearerTokenAuthenticationFilter.class)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Bearer tokens in a header, no cookies: there is no ambient credential for CSRF to abuse.
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }

    /** Realm roles from the flat {@value Caller#ROLES_CLAIM} claim become {@code ROLE_<name>} authorities. */
    private static JwtAuthenticationConverter authentication() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName(Caller.ROLES_CLAIM);
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return converter;
    }
}
