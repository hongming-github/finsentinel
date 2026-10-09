package com.finsentinel.platform.security;

import com.finsentinel.platform.PlatformTest;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import static com.finsentinel.platform.TestTokens.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The realm the platform actually runs with (deploy/keycloak/finsentinel-realm.json) in a real Keycloak, and the
 * platform trusting it: the claims, audiences and roles the security rules depend on come from that file, so it is
 * tested like code. Off by default (a Keycloak container takes about 20 s and 600 MB).
 * Run: ./mvnw -pl agent-platform test -Dtest=KeycloakRealmTest -Dkeycloak=true -Dsurefire.failIfNoSpecifiedTests=false
 */
@PlatformTest
@TestPropertySource(properties = "finsentinel.test.external-issuer=true")
@Testcontainers
@EnabledIfSystemProperty(named = "keycloak", matches = "true")
class KeycloakRealmTest {

    @Container
    static final GenericContainer<?> keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:26.7.5")
            .withCommand("start-dev", "--import-realm")
            .withCopyFileToContainer(MountableFile.forHostPath("../deploy/keycloak/finsentinel-realm.json"),
                    "/opt/keycloak/data/import/finsentinel-realm.json")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/finsentinel/.well-known/openid-configuration")
                    .forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

    @DynamicPropertySource
    static void realm(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", KeycloakRealmTest::issuer);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JsonMapper json;

    @Test
    void realmTokensCarryWhatTheRulesNeed() throws Exception {
        assertThat(claims(password("alice"))).containsEntry("tenant", "straits").containsEntry("preferred_username", "alice");
        assertThat(claims(password("bob"))).containsEntry("tenant", "harbour");
        Map<String, Object> platform = claims(clientCredentials("platform-service", "platform-service-dev-secret"));
        assertThat(platform).doesNotContainKey("tenant");
        assertThat(platform.get("aud").toString()).contains("mcp-banking").contains("mcp-compliance")
                .doesNotContain("finsentinel-platform");
    }

    @Test
    void analystsReachOnlyTheirOwnBank() throws Exception {
        String alice = password("alice");
        String bob = password("bob");

        mvc.perform(get("/api/tenants/straits/cases").with(bearer(alice))).andExpect(status().isOk());
        mvc.perform(get("/api/tenants/harbour/cases").with(bearer(alice))).andExpect(status().isNotFound());
        mvc.perform(get("/api/tenants/harbour/cases").with(bearer(bob))).andExpect(status().isOk());
        mvc.perform(get("/api/tenants/straits/llm-usage").with(bearer(alice))).andExpect(status().isForbidden());
    }

    @Test
    void supervisorsReadCostsButDoNotDecide() throws Exception {
        String sam = password("sam");

        mvc.perform(get("/api/tenants/straits/llm-usage").with(bearer(sam))).andExpect(status().isOk());
        mvc.perform(post("/api/tenants/straits/cases/c-1/decision").with(bearer(sam))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"FALSE_POSITIVE\"}")
                        .header("If-Match", "\"1\""))
                .andExpect(status().isForbidden());
    }

    @Test
    void serviceTokensOnlyWorkWhereTheyAreMeantTo() throws Exception {
        String assistant = clientCredentials("analyst-assistant", "analyst-assistant-dev-secret");
        String platform = clientCredentials("platform-service", "platform-service-dev-secret");

        mvc.perform(get("/api/tenants/straits/cases").with(bearer(assistant))).andExpect(status().isForbidden());
        // Issued for the MCP servers, not for the platform: wrong audience.
        mvc.perform(post("/mcp").with(bearer(platform)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private static String issuer() {
        return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080) + "/realms/finsentinel";
    }

    private String password(String user) throws Exception {
        return token(Map.of("grant_type", "password", "client_id", "analyst-cli", "username", user, "password", user));
    }

    private String clientCredentials(String client, String secret) throws Exception {
        return token(Map.of("grant_type", "client_credentials", "client_id", client, "client_secret", secret));
    }

    private String token(Map<String, String> form) throws Exception {
        String body = form.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("&"));
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(issuer() + "/protocol/openid-connect/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            return json.readTree(response.body()).get("access_token").asString();
        }
    }

    private static Map<String, Object> claims(String token) throws Exception {
        JWTClaimsSet claims = SignedJWT.parse(token).getJWTClaimsSet();
        return claims.toJSONObject();
    }
}
