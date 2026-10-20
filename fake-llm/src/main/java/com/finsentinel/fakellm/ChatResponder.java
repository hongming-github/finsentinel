package com.finsentinel.fakellm;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers an OpenAI Chat Completions request the way a cooperative model would, without one:
 * <ol>
 *   <li>Offered tools and no tool results yet: call the alert's transactions and, when offered, the customer profile
 *       (an outside system behind mcp-banking, so the chaos drills reach it) and the regulation search (all read-only;
 *       arguments the tools treat as "as much as allowed").</li>
 *   <li>Otherwise: the final answer, generated from the requested JSON schema, citing only transaction ids and chunk
 *       ids that tool results in this conversation returned, so the platform's validator accepts it.</li>
 * </ol>
 * The verdict is always NEEDS_REVIEW at 50: valid for every case (it neither clears nor confirms), and it goes through
 * the platform's reviewer, which this responder approves. The case assistant (ADR 0028) gets its question back as the
 * condensed question, a regulation search when the question names a regulatory topic, and an answer that cites
 * nothing (always grounded). Stateless: everything comes from the request.
 */
final class ChatResponder {

    static final String MODEL = "fake-llm";
    static final String DISPOSITION = "NEEDS_REVIEW";
    static final int RISK_SCORE = 50;
    private static final List<String> FIRST_TOOLS = List.of("getAlertTransactions", "getCustomerProfile",
            "searchRegulations");
    private static final String SEARCH_QUERY = "customer liability and reporting duties for a suspicious payment";
    private static final Pattern PASSAGE_ID = Pattern.compile("^\\[([^\\]\\s]+)]$", Pattern.MULTILINE);
    private static final Pattern NOTED_TXNS = Pattern.compile("transactions \\[([^\\]]*)]");
    private static final Pattern NOTED_CHUNKS = Pattern.compile("chunkIds \\[([^\\]]*)]");
    private static final int MAX_CITED_TXNS = 5;
    private static final int MAX_CITED_CHUNKS = 2;
    private static final String LATEST_QUESTION = "The analyst's latest question:\n";
    private static final Pattern REGULATORY = Pattern.compile(
            "regulat|guideline|eupg|\\bmas\\b|cooling.off|liabilit|obligat", Pattern.CASE_INSENSITIVE);

    private final JsonMapper json;
    private final AtomicLong sequence = new AtomicLong();

    ChatResponder(JsonMapper json) {
        this.json = json;
    }

    ObjectNode respond(JsonNode request) {
        List<String> tools = new ArrayList<>();
        request.path("tools").forEach(t -> tools.add(t.path("function").path("name").asString()));
        Evidence evidence = evidence(request.path("messages"), lastUserText(request));
        ObjectNode message = json.createObjectNode().put("role", "assistant");
        String finish;
        if (!tools.isEmpty() && !evidence.toolResultsSeen()) {
            message.putNull("content");
            ArrayNode calls = message.putArray("tool_calls");
            for (String tool : FIRST_TOOLS) {
                if (tools.contains(tool)) {
                    calls.add(toolCall(tool, tool.equals("searchRegulations")
                            ? json.createObjectNode().put("query", SEARCH_QUERY) : json.createObjectNode()));
                }
            }
            if (calls.isEmpty()) {
                // A specialist with other tools: its first one, with no arguments (every tool parameter is optional).
                calls.add(toolCall(tools.getFirst(), json.createObjectNode()));
            }
            finish = "tool_calls";
        } else {
            JsonNode schemaRoot = request.path("response_format").path("json_schema").path("schema");
            message.put("content", schemaRoot.isMissingNode()
                    ? "Synthetic notes by fake-llm: disposition " + DISPOSITION + ", risk score " + RISK_SCORE
                            + ", transactions " + evidence.txnIds() + ", chunkIds " + evidence.chunkIds() + "."
                    : json.writeValueAsString(fill(schemaRoot, schemaRoot, null, evidence, lastUserText(request))));
            finish = "stop";
        }
        ObjectNode response = json.createObjectNode()
                .put("id", "chatcmpl-fake-" + sequence.incrementAndGet())
                .put("object", "chat.completion")
                .put("created", System.currentTimeMillis() / 1000)
                .put("model", MODEL);
        ObjectNode choice = response.putArray("choices").addObject().put("index", 0).put("finish_reason", finish);
        choice.set("message", message);
        int prompt = estimateTokens(request.path("messages").toString() + request.path("tools").toString());
        int completion = estimateTokens(message.toString());
        ObjectNode usage = response.putObject("usage").put("prompt_tokens", prompt).put("completion_tokens", completion)
                .put("total_tokens", prompt + completion);
        usage.putObject("prompt_tokens_details").put("cached_tokens", 0).put("cache_write_tokens", 0);
        return response;
    }

    private ObjectNode toolCall(String name, ObjectNode arguments) {
        ObjectNode call = json.createObjectNode().put("id", "call_fake_" + sequence.incrementAndGet())
                .put("type", "function");
        call.putObject("function").put("name", name).put("arguments", json.writeValueAsString(arguments));
        return call;
    }

    /** Ids the tools returned in this conversation: transactions (objects with an amount) and regulation chunks. */
    record Evidence(boolean toolResultsSeen, List<String> txnIds, List<String> chunkIds) {
    }

    /**
     * From the tool results; without any, from notes in the user's text: the platform's two-step path (for providers
     * that cannot combine tools and a schema) hands the notes of the tool call to a second, tool-less call.
     */
    private Evidence evidence(JsonNode messages, String userText) {
        boolean seen = false;
        Set<String> txnIds = new LinkedHashSet<>();
        Set<String> chunkIds = new LinkedHashSet<>();
        for (JsonNode m : messages) {
            if (!m.path("role").asString().equals("tool")) {
                continue;
            }
            seen = true;
            try {
                collect(json.readTree(m.path("content").asString()), txnIds, chunkIds);
            } catch (JacksonException notJson) {
                // A plain-text tool result carries no ids.
            }
        }
        if (!seen) {
            listed(NOTED_TXNS, userText, txnIds);
            listed(NOTED_CHUNKS, userText, chunkIds);
        }
        return new Evidence(seen, List.copyOf(txnIds), List.copyOf(chunkIds));
    }

    /** "transactions [t-1, t-2]" as this responder writes its notes. */
    private static void listed(Pattern pattern, String text, Set<String> into) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            for (String id : m.group(1).split(",", -1)) {
                if (!id.isBlank()) {
                    into.add(id.strip());
                }
            }
        }
    }

    private static void collect(JsonNode node, Set<String> txnIds, Set<String> chunkIds) {
        if (node.isObject()) {
            if (node.has("amount") && node.path("id").isString()) {
                txnIds.add(node.path("id").asString());
            }
            if (node.path("chunkId").isString()) {
                chunkIds.add(node.path("chunkId").asString());
            }
            node.properties().forEach(e -> collect(e.getValue(), txnIds, chunkIds));
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, txnIds, chunkIds));
        }
    }

    private static String lastUserText(JsonNode request) {
        String text = "";
        for (JsonNode m : request.path("messages")) {
            if (m.path("role").asString().equals("user")) {
                JsonNode content = m.path("content");
                text = content.isString() ? content.asString() : content.toString();
            }
        }
        return text;
    }

    /** A value for {@code schema}; {@code property} is the name it is filled for, which picks the meaningful values. */
    private JsonNode fill(JsonNode root, JsonNode schema, String property, Evidence evidence, String userText) {
        JsonNode resolved = resolve(root, schema);
        if (property != null) {
            JsonNode special = special(root, resolved, property, evidence, userText);
            if (special != null) {
                return special;
            }
        }
        if (resolved.has("enum")) {
            return resolved.path("enum").get(0);
        }
        return switch (type(resolved)) {
            case "object" -> {
                ObjectNode object = json.createObjectNode();
                resolved.path("properties").properties().forEach(p ->
                        object.set(p.getKey(), fill(root, p.getValue(), p.getKey(), evidence, userText)));
                yield object;
            }
            case "array" -> json.createArrayNode();
            case "integer", "number" -> json.getNodeFactory().numberNode(0);
            case "boolean" -> json.getNodeFactory().booleanNode(false);
            case "null" -> json.getNodeFactory().nullNode();
            default -> json.getNodeFactory().stringNode("synthetic");
        };
    }

    private JsonNode special(JsonNode root, JsonNode schema, String property, Evidence evidence, String userText) {
        return switch (property) {
            case "disposition" -> json.getNodeFactory().stringNode(DISPOSITION);
            case "riskScore" -> json.getNodeFactory().numberNode(RISK_SCORE);
            case "approved" -> json.getNodeFactory().booleanNode(true);
            case "summary" -> json.getNodeFactory().stringNode("Synthetic analysis by fake-llm: no model was called.");
            case "recommendedActions" -> json.createArrayNode().add("Review the case manually.");
            case "evidenceTxnIds" -> strings(evidence.txnIds(), MAX_CITED_TXNS);
            case "sourceChunkIds" -> strings(evidence.chunkIds(), MAX_CITED_CHUNKS);
            case "findings" -> evidence.txnIds().isEmpty() ? json.createArrayNode()
                    : json.createArrayNode().add(fillItem(root, schema, Map.of("statement",
                    "Synthetic finding over the alert's transactions."), evidence, userText));
            case "regulatoryNotes" -> evidence.chunkIds().isEmpty() ? json.createArrayNode()
                    : json.createArrayNode().add(fillItem(root, schema, Map.of("statement",
                    "Synthetic note from the passages the search returned."), evidence, userText));
            case "grades" -> grades(userText);
            case "standaloneQuestion" -> json.getNodeFactory().stringNode(latestQuestion(userText));
            case "needsRegulations" -> json.getNodeFactory().booleanNode(
                    REGULATORY.matcher(latestQuestion(userText)).find());
            case "searchQuery" -> json.getNodeFactory().stringNode(
                    REGULATORY.matcher(latestQuestion(userText)).find() ? SEARCH_QUERY : "");
            case "answer" -> json.getNodeFactory().stringNode("Synthetic answer by fake-llm: no model was called.");
            default -> null;
        };
    }

    private JsonNode fillItem(JsonNode root, JsonNode arraySchema, Map<String, String> texts, Evidence evidence,
                              String userText) {
        JsonNode item = fill(root, arraySchema.path("items"), null, evidence, userText);
        if (item instanceof ObjectNode object) {
            texts.forEach(object::put);
        }
        return item;
    }

    /** The question the assistant's condensing call is about; the whole text for any other call. */
    private static String latestQuestion(String userText) {
        int at = userText.lastIndexOf(LATEST_QUESTION);
        return at < 0 ? userText : userText.substring(at + LATEST_QUESTION.length()).strip();
    }

    /** The reranker's answer: every passage id in the question graded 2, so the fused order stands. */
    private ArrayNode grades(String userText) {
        ArrayNode grades = json.createArrayNode();
        Matcher m = PASSAGE_ID.matcher(userText);
        while (m.find()) {
            grades.addObject().put("id", m.group(1)).put("grade", 2);
        }
        return grades;
    }

    private ArrayNode strings(List<String> values, int max) {
        ArrayNode array = json.createArrayNode();
        values.stream().limit(max).forEach(array::add);
        return array;
    }

    private static JsonNode resolve(JsonNode root, JsonNode schema) {
        String ref = schema.path("$ref").asString("");
        if (ref.startsWith("#/")) {
            JsonNode target = root;
            for (String part : ref.substring(2).split("/", -1)) {
                target = target.path(part);
            }
            return resolve(root, target);
        }
        return schema;
    }

    /** The first non-null type; schemas list nullable fields as ["string", "null"]. */
    private static String type(JsonNode schema) {
        JsonNode type = schema.path("type");
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (!t.asString().equals("null")) {
                    return t.asString();
                }
            }
            return "null";
        }
        return type.isString() ? type.asString() : schema.has("properties") ? "object" : "string";
    }

    /** Roughly four characters per token, enough for cost and load figures that do not matter here. */
    static int estimateTokens(String text) {
        return Math.max(1, text.length() / 4);
    }
}
