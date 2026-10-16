package com.finsentinel.loadtest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Access tokens from the Keycloak realm, one per identity, shared by all virtual users and fetched again shortly before
 * they expire (realm tokens live 5 minutes). The load is on the platform, not on Keycloak: a real analyst signs in once
 * per session, not once per request. The fetch blocks the calling thread, which happens once per identity every few
 * minutes.
 */
final class Tokens {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client = HttpClient.newHttpClient();
    private final URI tokenUrl;
    private final Duration refreshMargin;
    private final Map<String, Token> cache = new ConcurrentHashMap<>();

    Tokens(LoadSettings settings) {
        this.tokenUrl = URI.create(settings.string("token.url"));
        this.refreshMargin = settings.duration("token.refresh-margin");
    }

    /** A realm user through the dev-only password grant of the analyst CLI client (README step 7). */
    String user(String username) {
        return token("user:" + username, Map.of("grant_type", "password", "client_id", "analyst-cli",
                "username", username, "password", username));
    }

    /** A service account through the client-credentials grant (the MCP assistant). */
    String client(String clientId, String secret) {
        return token("client:" + clientId, Map.of("grant_type", "client_credentials", "client_id", clientId,
                "client_secret", secret));
    }

    private String token(String key, Map<String, String> form) {
        return cache.compute(key, (k, current) ->
                current != null && Instant.now().isBefore(current.expiresAt().minus(refreshMargin))
                        ? current : fetch(form)).value();
    }

    private Token fetch(Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(tokenUrl)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("token request failed: " + response.statusCode() + " "
                        + response.body());
            }
            JsonNode json = JSON.readTree(response.body());
            return new Token(json.get("access_token").asString(),
                    Instant.now().plusSeconds(json.get("expires_in").asLong()));
        } catch (IOException e) {
            throw new IllegalStateException("token request failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while fetching a token", e);
        }
    }

    private record Token(String value, Instant expiresAt) {
    }
}
