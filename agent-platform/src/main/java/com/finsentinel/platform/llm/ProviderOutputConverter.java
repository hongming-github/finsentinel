package com.finsentinel.platform.llm;

import org.springframework.ai.converter.BeanOutputConverter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Spring AI's bean converter, adjusted to what a provider accepts (ADR 0012, found by the phase 7 evaluation on Groq):
 * without {@code schemaFormatsOnNonStrings}, the schema loses "format" on non-string types: Groq rejects
 * {@code "format": "int32"} on an integer and failed every structured call with an int field. For OpenAI the schema is
 * unchanged.
 */
final class ProviderOutputConverter<T> extends BeanOutputConverter<T> {

    private static final JsonMapper SCHEMA_MAPPER = JsonMapper.builder().build();
    private static final ThreadLocal<Boolean> STRIP_FORMATS = ThreadLocal.withInitial(() -> false);

    private ProviderOutputConverter(Class<T> type) {
        super(type);
    }

    static <T> ProviderOutputConverter<T> of(Class<T> type, LlmProperties.Provider provider) {
        // The schema is generated in the superclass constructor, before this class's fields exist.
        STRIP_FORMATS.set(!provider.schemaFormatsOnNonStrings());
        try {
            return new ProviderOutputConverter<>(type);
        } finally {
            STRIP_FORMATS.remove();
        }
    }

    @Override
    protected String generateSchema() {
        String schema = super.generateSchema();
        if (!STRIP_FORMATS.get()) {
            return schema;
        }
        JsonNode tree = SCHEMA_MAPPER.readTree(schema);
        stripNonStringFormats(tree);
        return SCHEMA_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
    }

    static void stripNonStringFormats(JsonNode node) {
        if (node instanceof ObjectNode object) {
            JsonNode type = object.get("type");
            boolean string = type != null && (type.isString() ? type.asString().equals("string")
                    : type.isArray() && type.valueStream().anyMatch(t -> t.asString().equals("string")));
            if (object.has("format") && !string) {
                object.remove("format");
            }
        }
        if (node != null && node.isContainer()) {
            node.forEach(ProviderOutputConverter::stripNonStringFormats);
        }
    }
}
