package com.finsentinel.mcp.banking;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** The WireMock fake systems from deploy/systems, the same stubs docker compose serves locally. */
final class FakeSystems {

    private FakeSystems() {
    }

    static GenericContainer<?> container() {
        return new GenericContainer<>("wiremock/wiremock:3.13.2")
                .withExposedPorts(8080)
                .withCopyFileToContainer(MountableFile.forHostPath("../deploy/systems/mappings"), "/home/wiremock/mappings")
                .waitingFor(Wait.forHttp("/__admin/health"));
    }

    static String url(GenericContainer<?> systems, String path) {
        return "http://" + systems.getHost() + ":" + systems.getMappedPort(8080) + path;
    }

    /** Adds a stub for one test, e.g. a failing or slow backend. */
    static void stub(GenericContainer<?> systems, String mappingJson) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url(systems, "/__admin/mappings")))
                        .POST(HttpRequest.BodyPublishers.ofString(mappingJson)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201) {
            throw new IllegalStateException("stub rejected: " + response.body());
        }
    }
}
