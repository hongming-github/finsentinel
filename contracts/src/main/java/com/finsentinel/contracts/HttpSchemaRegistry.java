package com.finsentinel.contracts;

import org.apache.avro.Schema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link SchemaRegistry} over HTTP, Confluent REST API (Apicurio: {@code http://host:8080/apis/ccompat/v7}). Schemas by
 * id are immutable, so they are cached for the life of the client.
 */
public final class HttpSchemaRegistry implements SchemaRegistry {

    private static final String CONTENT_TYPE = "application/vnd.schemaregistry.v1+json";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String baseUrl;
    private final HttpClient http;
    private final Duration timeout;
    private final Map<Integer, Schema> byId = new ConcurrentHashMap<>();

    public HttpSchemaRegistry(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public int idOf(String subject, Schema schema) {
        HttpResponse<String> response = send("POST", "/subjects/" + encode(subject), schemaBody(schema));
        if (response.statusCode() == 404) {
            throw new SchemaNotRegisteredException("schema for " + subject + " is not registered (run SchemaRegistration)");
        }
        return ok(response).get("id").asInt();
    }

    @Override
    public Schema schemaById(int id) {
        return byId.computeIfAbsent(id, i -> {
            HttpResponse<String> response = send("GET", "/schemas/ids/" + i, null);
            if (response.statusCode() == 404) {
                throw new SchemaNotRegisteredException("no schema with id " + i);
            }
            return new Schema.Parser().parse(ok(response).get("schema").asString());
        });
    }

    @Override
    public int register(String subject, Schema schema) {
        HttpResponse<String> response = send("POST", "/subjects/" + encode(subject) + "/versions", schemaBody(schema));
        if (response.statusCode() == 409) {
            throw new IncompatibleSchemaException(subject + ": " + response.body());
        }
        return ok(response).get("id").asInt();
    }

    @Override
    public void setCompatibility(String subject, String level) {
        ok(send("PUT", "/config/" + encode(subject), JSON.writeValueAsString(Map.of("compatibility", level))));
    }

    private static String schemaBody(Schema schema) {
        return JSON.writeValueAsString(Map.of("schema", schema.toString()));
    }

    private HttpResponse<String> send(String method, String path, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout)
                .header("Accept", CONTENT_TYPE);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", CONTENT_TYPE).method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("schema registry " + baseUrl + " unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode ok(HttpResponse<String> response) {
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("schema registry answered " + response.statusCode() + ": " + response.body());
        }
        return JSON.readTree(response.body());
    }

    private static String encode(String subject) {
        return URLEncoder.encode(subject, StandardCharsets.UTF_8);
    }
}
