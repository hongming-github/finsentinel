package com.finsentinel.platform.analysis;

import org.bsc.langgraph4j.serializer.StateSerializer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * JSON codec for {@link CaseGraphState}, used twice: LangGraph4j clones the state through it before every node, and
 * the checkpoint saver persists it with it. Cloning through the same JSON as the checkpoints means a state that
 * would not survive a restart already fails in a normal run, not only after a crash.
 */
@Component
class CaseGraphStateSerializer extends StateSerializer<CaseGraphState> {

    private final JsonMapper json;

    CaseGraphStateSerializer(JsonMapper json) {
        super(CaseGraphState::new);
        this.json = json;
    }

    @Override
    public String contentType() {
        return "application/json";
    }

    @Override
    public String writeDataAsString(CaseGraphState state) {
        return toJson(state.data());
    }

    /** Same as {@link #writeDataAsString}, for raw checkpoint state, without the checked exception. */
    String toJson(Map<String, Object> data) {
        data.keySet().forEach(CaseGraphStateSerializer::typeOf);
        return json.writeValueAsString(data);
    }

    @Override
    public CaseGraphState readDataFromString(String string) {
        Map<String, Object> data = new HashMap<>();
        for (Map.Entry<String, JsonNode> property : json.readTree(string).properties()) {
            if (!property.getValue().isNull()) {
                data.put(property.getKey(), json.treeToValue(property.getValue(), typeOf(property.getKey())));
            }
        }
        return new CaseGraphState(data);
    }

    private static Class<?> typeOf(String key) {
        Class<?> type = CaseGraphState.TYPES.get(key);
        if (type == null) {
            throw new IllegalStateException("undeclared graph state key '" + key + "'; add it to CaseGraphState.TYPES");
        }
        return type;
    }
}
