package com.finsentinel.platform.systems;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * One MCP server, connected on first use. The platform starts and keeps working when a server is down: a failed call
 * drops the connection, and the next call connects again. Thread-safe. Every request carries the platform's own
 * access token; no token (identity provider down) fails the call like a server that is down.
 */
class McpServerConnection implements AutoCloseable {

    private final String name;
    private final URI url;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final JsonMapper json;
    private final Supplier<String> bearerToken;
    private McpSyncClient client;

    McpServerConnection(String name, URI url, Duration connectTimeout, Duration requestTimeout, JsonMapper json,
                        Supplier<String> bearerToken) {
        this.name = name;
        this.url = url;
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
        this.json = json;
        this.bearerToken = bearerToken;
    }

    <T> T call(String tool, Map<String, Object> arguments, Class<T> type) {
        McpSchema.CallToolResult result;
        try {
            result = client().callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
        } catch (RuntimeException e) {
            disconnect();
            throw new SystemUnavailableException(name, tool + ": " + e);
        }
        return parse(name, tool, result, type, json);
    }

    /** A tool error (the server reached its backend and failed) is reported, but the connection is fine. */
    static <T> T parse(String system, String tool, McpSchema.CallToolResult result, Class<T> type, JsonMapper json) {
        String text = result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(c -> ((McpSchema.TextContent) c).text())
                .findFirst()
                .orElse("");
        if (Boolean.TRUE.equals(result.isError())) {
            throw new SystemUnavailableException(system, tool + " failed: " + abbreviate(text));
        }
        try {
            return json.readValue(text, type);
        } catch (JacksonException e) {
            throw new SystemUnavailableException(system, tool + " returned unreadable content: " + abbreviate(text));
        }
    }

    /**
     * The JSON mapper and schema validator are passed in, never looked up: the SDK's default lookup is a
     * {@code ServiceLoader} on the thread's context class loader, which inside the packaged jar cannot see the
     * SDK's own Jackson module when the call comes from a pool thread.
     */
    private synchronized McpSyncClient client() {
        if (client == null) {
            McpSyncClient fresh = McpClient.sync(HttpClientStreamableHttpTransport.builder(url.toString())
                            .clientBuilder(HttpClient.newBuilder().connectTimeout(connectTimeout))
                            .httpRequestCustomizer((request, method, endpoint, body, context) ->
                                    request.header("Authorization", "Bearer " + bearerToken.get()))
                            .jsonMapper(new JacksonMcpJsonMapper(json))
                            .build())
                    .jsonSchemaValidator(new DefaultJsonSchemaValidator(json))
                    .clientInfo(McpSchema.Implementation.builder("finsentinel-platform", "0.1.0").build())
                    .requestTimeout(requestTimeout)
                    .initializationTimeout(requestTimeout)
                    .build();
            try {
                fresh.initialize();
            } catch (RuntimeException e) {
                fresh.close();
                throw e;
            }
            client = fresh;
        }
        return client;
    }

    private synchronized void disconnect() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    @Override
    public void close() {
        disconnect();
    }

    private static String abbreviate(String text) {
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
