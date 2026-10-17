package com.finsentinel.fakellm;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Over real HTTP, as the OpenAI SDK talks to it. */
class FakeLlmTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String TOOLS = """
            [{"type":"function","function":{"name":"getAccountProfile","parameters":{"type":"object"}}},
             {"type":"function","function":{"name":"getAlertTransactions","parameters":{"type":"object"}}},
             {"type":"function","function":{"name":"getCustomerProfile","parameters":{"type":"object"}}},
             {"type":"function","function":{"name":"searchRegulations","parameters":{"type":"object"}}}]""";
    /** The shape Spring AI generates for a record: nested records as $defs, nullable fields as type arrays. */
    private static final String REPORT_SCHEMA = """
            {"name":"CaseReport","strict":true,"schema":{"type":"object","properties":{
              "disposition":{"type":"string","enum":["LIKELY_FRAUD","NEEDS_REVIEW","LIKELY_LEGITIMATE"]},
              "riskScore":{"type":"integer"},
              "summary":{"type":"string"},
              "findings":{"type":"array","items":{"$ref":"#/$defs/Finding"}},
              "recommendedActions":{"type":"array","items":{"type":"string"}},
              "regulatoryNotes":{"type":["array","null"],"items":{"$ref":"#/$defs/RegulatoryNote"}}},
              "$defs":{
                "Finding":{"type":"object","properties":{"statement":{"type":"string"},
                  "evidenceTxnIds":{"type":"array","items":{"type":"string"}}}},
                "RegulatoryNote":{"type":"object","properties":{"statement":{"type":"string"},
                  "sourceChunkIds":{"type":"array","items":{"type":"string"}}}}}}}""";

    private static FakeLlm fake;

    @BeforeAll
    static void start() throws IOException {
        fake = new FakeLlm(0);
    }

    @AfterAll
    static void stop() {
        fake.close();
    }

    @AfterEach
    void normal() throws Exception {
        call("DELETE", "/admin/behaviour", "");
    }

    @Test
    void offeredToolsAreCalledFirst() throws Exception {
        JsonNode response = chat("/v1", """
                {"model":"gpt-6-luna","tools":%s,"messages":[{"role":"system","content":"s"},
                 {"role":"user","content":"Investigate this case."}],"response_format":{"type":"json_schema",
                 "json_schema":%s}}""".formatted(TOOLS, REPORT_SCHEMA));

        JsonNode choice = response.path("choices").get(0);
        assertThat(choice.path("finish_reason").asString()).isEqualTo("tool_calls");
        assertThat(choice.path("message").path("tool_calls").findValuesAsString("name"))
                .containsExactly("getAlertTransactions", "getCustomerProfile", "searchRegulations");
        assertThat(response.path("model").asString()).isEqualTo("fake-llm");
        assertThat(response.path("usage").path("prompt_tokens").asInt()).isPositive();
    }

    @Test
    void finalAnswerFitsTheSchemaAndCitesOnlyWhatTheToolsReturned() throws Exception {
        String transactions = JSON.writeValueAsString("""
                {"transactions":[{"id":"t-1","amount":"40.00 SGD"},{"id":"t-2","amount":"900.00 SGD"}],
                 "travel":[{"fromTxn":"t-1","toTxn":"t-2","verdict":"IMPOSSIBLE"}]}""");
        String passages = JSON.writeValueAsString("""
                {"passages":[{"chunkId":"srf-2024:6.2","text":"..."},{"chunkId":"eupg-2024:4.7","text":"..."},
                 {"chunkId":"eupg-2024:3.14","text":"..."}],"note":null}""");
        JsonNode response = chat("/v1", """
                {"model":"gpt-6-luna","tools":%s,"response_format":{"type":"json_schema","json_schema":%s},
                 "messages":[{"role":"user","content":"Investigate this case."},
                  {"role":"assistant","tool_calls":[{"id":"c1","type":"function",
                    "function":{"name":"getAlertTransactions","arguments":"{}"}}]},
                  {"role":"tool","tool_call_id":"c1","content":%s},
                  {"role":"tool","tool_call_id":"c2","content":%s}]}""".formatted(TOOLS, REPORT_SCHEMA,
                transactions, passages));

        JsonNode choice = response.path("choices").get(0);
        assertThat(choice.path("finish_reason").asString()).isEqualTo("stop");
        JsonNode report = JSON.readTree(choice.path("message").path("content").asString());
        assertThat(report.path("disposition").asString()).isEqualTo("NEEDS_REVIEW");
        assertThat(report.path("riskScore").asInt()).isEqualTo(50);
        assertThat(report.path("findings").get(0).path("evidenceTxnIds").valueStream().map(JsonNode::asString))
                .containsExactly("t-1", "t-2");
        assertThat(report.path("regulatoryNotes").get(0).path("sourceChunkIds").valueStream().map(JsonNode::asString))
                .containsExactly("srf-2024:6.2", "eupg-2024:4.7");
    }

    @Test
    void reviewerApprovesAndRerankerGradesEveryPassage() throws Exception {
        JsonNode verdict = content(chat("/v1", """
                {"model":"gpt-6-luna","messages":[{"role":"user","content":"Review this case report."}],
                 "response_format":{"type":"json_schema","json_schema":{"name":"Verdict","schema":{"type":"object",
                 "properties":{"approved":{"type":"boolean"},"issues":{"type":"array","items":{"type":"string"}}}}}}}"""));
        JsonNode grades = content(chat("/v1", """
                {"model":"gpt-6-luna","messages":[{"role":"user",
                  "content":"Question: who pays?\\n\\nPassages:\\n[srf-2024:6.2]\\ntext\\n\\n[eupg-2024:4.7]\\ntext\\n"}],
                 "response_format":{"type":"json_schema","json_schema":{"name":"Grades","schema":{"type":"object",
                 "properties":{"grades":{"type":"array","items":{"type":"object","properties":{
                   "id":{"type":"string"},"grade":{"type":"integer"}}}}}}}}}"""));

        assertThat(verdict.path("approved").asBoolean()).isTrue();
        assertThat(verdict.path("issues").isEmpty()).isTrue();
        assertThat(grades.path("grades").findValuesAsString("id")).containsExactly("srf-2024:6.2", "eupg-2024:4.7");
    }

    /** The platform's two-step path: tools end in notes, a second call without tools turns them into the schema. */
    @Test
    void notesOfTheToolCallAreTheEvidenceOfTheStructuringCall() throws Exception {
        String toolResult = JSON.writeValueAsString("{\"transactions\":[{\"id\":\"t-9\",\"amount\":\"1.00 SGD\"}]}");
        JsonNode notes = chat("/groq/v1", "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"Investigate.\"},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"content\":" + toolResult + "}]}");
        String noteText = notes.path("choices").get(0).path("message").path("content").asString();

        JsonNode report = content(chat("/groq/v1", "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":"
                + JSON.writeValueAsString("Investigate.\n\nYour notes:\n" + noteText) + "}],"
                + "\"response_format\":{\"type\":\"json_schema\",\"json_schema\":" + REPORT_SCHEMA + "}}"));

        assertThat(noteText).contains("transactions [t-9]");
        assertThat(report.path("findings").get(0).path("evidenceTxnIds").get(0).asString()).isEqualTo("t-9");
    }

    @Test
    void embeddingsAreDeterministicUnitVectors() throws Exception {
        JsonNode response = JSON.readTree(call("POST", "/v1/embeddings",
                "{\"model\":\"text-embedding-3-small\",\"input\":[\"cooling off period\",\"cooling off period\"]}")
                .body());

        JsonNode first = response.path("data").get(0).path("embedding");
        assertThat(first.size()).isEqualTo(Embeddings.DEFAULT_DIMENSIONS);
        assertThat(first).isEqualTo(response.path("data").get(1).path("embedding"));
        double norm = first.valueStream().mapToDouble(JsonNode::asDouble).map(x -> x * x).sum();
        assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    /** A drill fails one provider and leaves the other answering, so the platform's fallback can be seen to work. */
    @Test
    void injectedFailuresHitOnlyTheNamedProvider() throws Exception {
        assertThat(call("POST", "/admin/behaviour?provider=openai",
                "{\"errorStatus\":503,\"errorRate\":1.0}").statusCode()).isEqualTo(200);
        String request = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        HttpResponse<String> openai = call("POST", "/v1/chat/completions", request);
        HttpResponse<String> groq = call("POST", "/groq/v1/chat/completions", request);

        assertThat(openai.statusCode()).isEqualTo(503);
        assertThat(openai.body()).contains("injected by fake-llm");
        assertThat(groq.statusCode()).isEqualTo(200);
        call("DELETE", "/admin/behaviour?provider=openai", "");
        assertThat(call("POST", "/v1/chat/completions", request).statusCode()).isEqualTo(200);
    }

    @Test
    void latencyIsAddedAndBadInputIsRefused() throws Exception {
        call("POST", "/admin/behaviour?provider=openai", "{\"latencyMs\":300}");
        long start = System.nanoTime();
        call("POST", "/v1/chat/completions", "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(300);
        assertThat(call("POST", "/admin/behaviour", "{\"errorStatus\":418}").statusCode()).isEqualTo(400);
        assertThat(call("POST", "/v1/chat/completions", "not json").statusCode()).isEqualTo(400);
    }

    private static JsonNode chat(String prefix, String body) throws Exception {
        HttpResponse<String> response = call("POST", prefix + "/chat/completions", body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static JsonNode content(JsonNode response) {
        return JSON.readTree(response.path("choices").get(0).path("message").path("content").asString());
    }

    private static HttpResponse<String> call(String method, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + fake.port() + path))
                .header("Content-Type", "application/json")
                .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
