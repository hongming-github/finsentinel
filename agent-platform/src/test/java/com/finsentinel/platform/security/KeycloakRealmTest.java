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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    static final GenericContainer<?> keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:26.8.1")
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

    /**
     * The console's sign-in (ADR 0027) the way a browser does it: authorization code with PKCE, a public client, no
     * password grant. The token reaches the same rules as any analyst's.
     */
    @Test
    void theConsoleSignsInWithPkceOnly() throws Exception {
        String alice = consoleSignIn("alice");
        assertThat(claims(alice)).containsEntry("tenant", "straits").containsEntry("azp", CONSOLE);
        assertThat(claims(alice).get("aud").toString()).contains("finsentinel-platform");
        mvc.perform(get("/api/tenants/straits/cases").with(bearer(alice))).andExpect(status().isOk());
        mvc.perform(get("/api/tenants/harbour/cases").with(bearer(alice))).andExpect(status().isNotFound());

        try (HttpClient http = HttpClient.newHttpClient()) {
            // No code challenge, or a redirect anywhere but the console: Keycloak refuses before any login page.
            HttpResponse<String> noChallenge = http.send(authorize(CONSOLE_REDIRECT, null),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(noChallenge.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l)
                    .startsWith(CONSOLE_REDIRECT).contains("error=invalid_request").contains("code_challenge_method"));
            HttpResponse<String> elsewhere = http.send(authorize("http://evil.example/callback",
                    challenge("x".repeat(43))), HttpResponse.BodyHandlers.ofString());
            assertThat(elsewhere.statusCode()).isEqualTo(400);
            assertThat(elsewhere.body()).contains("redirect_uri");
            // The password grant stays with the development CLI.
            HttpResponse<String> password = http.send(HttpRequest.newBuilder(URI.create(issuer() + "/protocol/openid-connect/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("grant_type", "password", "client_id", CONSOLE,
                            "username", "alice", "password", "alice")))).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(password.statusCode()).isEqualTo(400);
            assertThat(password.body()).contains("unauthorized_client");
        }
    }

    private static final String CONSOLE = "finsentinel-console";
    private static final String CONSOLE_REDIRECT = "http://localhost:8097/callback";
    private static final Pattern LOGIN_FORM = Pattern.compile("action=\"([^\"]+)\"");

    /**
     * Login page, form post with the user's password, the code from the redirect, then the code with the verifier.
     * The login cookies are carried by hand: Keycloak marks them Secure, which a browser still sends to localhost but
     * Java's CookieManager never sends over http.
     */
    private String consoleSignIn(String user) throws Exception {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> page = http.send(authorize(CONSOLE_REDIRECT, challenge(verifier)),
                    HttpResponse.BodyHandlers.ofString());
            Matcher action = LOGIN_FORM.matcher(page.body());
            assertThat(action.find()).as(page.body()).isTrue();
            String cookies = page.headers().allValues("Set-Cookie").stream()
                    .map(c -> c.substring(0, c.indexOf(';'))).collect(Collectors.joining("; "));
            HttpResponse<String> login = http.send(HttpRequest.newBuilder(URI.create(action.group(1).replace("&amp;", "&")))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Cookie", cookies)
                    .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("username", user, "password", user)))).build(),
                    HttpResponse.BodyHandlers.ofString());
            String location = login.headers().firstValue("Location").orElseThrow(() -> new AssertionError(login.body()));
            assertThat(location).startsWith(CONSOLE_REDIRECT);
            String code = location.replaceAll(".*[?&]code=([^&]+).*", "$1");
            return token(Map.of("grant_type", "authorization_code", "client_id", CONSOLE, "code", code,
                    "redirect_uri", CONSOLE_REDIRECT, "code_verifier", verifier));
        }
    }

    private static HttpRequest authorize(String redirect, String challenge) {
        String query = "client_id=" + CONSOLE + "&response_type=code&scope=openid&redirect_uri="
                + URLEncoder.encode(redirect, StandardCharsets.UTF_8)
                + (challenge == null ? "" : "&code_challenge=" + challenge + "&code_challenge_method=S256");
        return HttpRequest.newBuilder(URI.create(issuer() + "/protocol/openid-connect/auth?" + query)).GET().build();
    }

    private static String challenge(String verifier) throws Exception {
        byte[] sha = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha);
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
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

    private String token(Map<String, String> fields) throws Exception {
        String body = form(fields);
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
