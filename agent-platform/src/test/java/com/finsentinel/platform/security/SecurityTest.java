package com.finsentinel.platform.security;

import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.TestIssuer;
import com.finsentinel.platform.TestTokens;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.PlainHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static com.finsentinel.platform.TestData.uniqueTenant;
import static com.finsentinel.platform.TestTokens.bearer;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fails closed: no token, a token this platform must not accept, the wrong role, or another tenant's path. Every
 * request goes through the real chain and the production JWT decoder (keys from {@link TestIssuer}).
 */
@PlatformTest
class SecurityTest {

    /** Every tenant-scoped endpoint, GET unless it is the decision. */
    private static final List<String> TENANT_PATHS = List.of("/api/tenants/%s/cases", "/api/tenants/%s/cases/c-1",
            "/api/tenants/%s/alerts", "/api/tenants/%s/llm-usage", "/api/tenants/%s/cases/c-1/conversations",
            "/api/tenants/%s/cases/c-1/conversations/v-1");

    @Autowired
    MockMvc mvc;

    private final String tenant = uniqueTenant();

    @Test
    void noTokenIsUnauthorisedEverywhereButHealth() throws Exception {
        for (String path : TENANT_PATHS) {
            mvc.perform(get(path.formatted(tenant))).andExpect(status().isUnauthorized());
        }
        mvc.perform(decision(tenant)).andExpect(status().isUnauthorized());
        mvc.perform(startConversation(tenant)).andExpect(status().isUnauthorized());
        mvc.perform(question(tenant)).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void mcpWithoutTokenPointsToTheProtectedResourceMetadata() throws Exception {
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        containsString("resource_metadata=http://localhost/.well-known/oauth-protected-resource/mcp")));
        mvc.perform(get("/.well-known/oauth-protected-resource/mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorization_servers[0]").value(TestIssuer.uri()))
                .andExpect(jsonPath("$.resource").value("http://localhost/mcp"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong issuer", "wrong audience", "expired", "foreign key", "unsigned"})
    void tokensThePlatformMustNotAcceptAreRefused(String flaw) throws Exception {
        JWTClaimsSet.Builder claims = TestTokens.claims(tenant, "mallory", "analyst");
        String token = switch (flaw) {
            case "wrong issuer" -> TestTokens.sign(claims.issuer("http://localhost:1/realms/finsentinel").build());
            case "wrong audience" -> TestTokens.sign(claims.audience("mcp-banking").build());
            case "expired" -> TestTokens.sign(claims.expirationTime(Date.from(Instant.now().minus(Duration.ofMinutes(5))))
                    .build());
            case "foreign key" -> signedWithAnotherKey(claims.build());
            case "unsigned" -> new PlainJWT(new PlainHeader(), claims.build()).serialize();
            default -> throw new IllegalArgumentException(flaw);
        };

        mvc.perform(get("/api/tenants/{t}/cases", tenant).with(bearer(token))).andExpect(status().isUnauthorized());
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{}").with(bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rolesDecideWhatEachCallerMayDo() throws Exception {
        String analyst = TestTokens.analyst(tenant);
        String supervisor = TestTokens.supervisor(tenant);
        String assistant = TestTokens.assistant(tenant);
        String noRole = TestTokens.token(tenant, "nobody");

        mvc.perform(get("/api/tenants/{t}/cases", tenant).with(bearer(analyst))).andExpect(status().isOk());
        mvc.perform(get("/api/tenants/{t}/cases", tenant).with(bearer(supervisor))).andExpect(status().isOk());
        mvc.perform(get("/api/tenants/{t}/llm-usage", tenant).with(bearer(supervisor))).andExpect(status().isOk());
        mvc.perform(get("/api/tenants/{t}/llm-usage", tenant).with(bearer(analyst))).andExpect(status().isForbidden());
        mvc.perform(decision(tenant).with(bearer(supervisor))).andExpect(status().isForbidden());
        mvc.perform(get("/api/tenants/{t}/cases", tenant).with(bearer(assistant))).andExpect(status().isForbidden());
        mvc.perform(get("/api/tenants/{t}/cases", tenant).with(bearer(noRole))).andExpect(status().isForbidden());
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{}").with(bearer(noRole)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics").with(bearer(analyst))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics").with(bearer(supervisor))).andExpect(status().isOk());
        // The case assistant is the analyst's: supervisors and the MCP assistant client cannot use it.
        mvc.perform(get("/api/tenants/{t}/cases/c-1/conversations", tenant).with(bearer(analyst)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tenants/{t}/cases/c-1/conversations", tenant).with(bearer(supervisor)))
                .andExpect(status().isForbidden());
        mvc.perform(startConversation(tenant).with(bearer(supervisor))).andExpect(status().isForbidden());
        mvc.perform(question(tenant).with(bearer(supervisor))).andExpect(status().isForbidden());
        mvc.perform(question(tenant).with(bearer(assistant))).andExpect(status().isForbidden());
        mvc.perform(question(tenant).with(bearer(analyst))).andExpect(status().isNotFound());
    }

    @Test
    void anotherTenantsPathIsNotFoundForEveryEndpoint() throws Exception {
        String victim = uniqueTenant();
        String attackerAnalyst = TestTokens.analyst(tenant);
        String attackerSupervisor = TestTokens.supervisor(tenant);
        for (String path : TENANT_PATHS) {
            String token = path.endsWith("llm-usage") ? attackerSupervisor : attackerAnalyst;
            mvc.perform(get(path.formatted(victim)).with(bearer(token))).andExpect(status().isNotFound());
        }
        mvc.perform(decision(victim).with(bearer(attackerAnalyst))).andExpect(status().isNotFound());
        mvc.perform(startConversation(victim).with(bearer(attackerAnalyst))).andExpect(status().isNotFound());
        mvc.perform(question(victim).with(bearer(attackerAnalyst))).andExpect(status().isNotFound());
        // Encoded the way a client might try to slip past a string comparison.
        mvc.perform(get(URI.create("/api/tenants/" + victim.replace("t-", "t%2D") + "/cases")).with(bearer(attackerAnalyst)))
                .andExpect(status().isNotFound());
        // Decoded the same way as the controller's path variable: an encoded own tenant still matches.
        mvc.perform(get(URI.create("/api/tenants/" + tenant.replace("t-", "t%2D") + "/cases")).with(bearer(attackerAnalyst)))
                .andExpect(status().isOk());
    }

    @Test
    void aTokenWithoutATenantReachesNoTenantData() throws Exception {
        String noTenant = TestTokens.token(null, "service", "analyst", "supervisor");
        for (String path : TENANT_PATHS) {
            mvc.perform(get(path.formatted(tenant)).with(bearer(noTenant))).andExpect(status().isNotFound());
        }
    }

    @Test
    void anythingNotListedIsDenied() throws Exception {
        String analyst = TestTokens.analyst(tenant);
        mvc.perform(get("/api/anything").with(bearer(analyst))).andExpect(status().isForbidden());
        mvc.perform(post("/api/tenants/{t}/cases", tenant).with(bearer(analyst))).andExpect(status().isForbidden());
        mvc.perform(get("/api/tenants/{t}/cases/c-1/conversations/v-1/turns", tenant).with(bearer(analyst)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/env").with(bearer(analyst))).andExpect(status().isForbidden());
    }

    private static MockHttpServletRequestBuilder decision(String tenant) {
        return post("/api/tenants/{t}/cases/{id}/decision", tenant, "c-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"FALSE_POSITIVE\"}")
                .header("If-Match", "\"1\"");
    }

    private static MockHttpServletRequestBuilder startConversation(String tenant) {
        return post("/api/tenants/{t}/cases/{id}/conversations", tenant, "c-1");
    }

    private static MockHttpServletRequestBuilder question(String tenant) {
        return post("/api/tenants/{t}/cases/{id}/conversations/{c}/turns", tenant, "c-1", "v-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"why?\"}")
                .header("If-Match", "\"0\"");
    }

    private static String signedWithAnotherKey(JWTClaimsSet claims) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
            jwt.sign(new RSASSASigner(generator.generateKeyPair().getPrivate()));
            return jwt.serialize();
        } catch (NoSuchAlgorithmException | JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
