package com.finsentinel.platform.analysis;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.serializer.Serializer;
import com.alibaba.cloud.ai.graph.serializer.plain_text.PlainTextStateSerializer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.HashMap;
import java.util.Map;

/**
 * JSON codec (Jackson 3) for the analysis graph's state, used twice: the graph clones the state through it before
 * every checkpoint, and the checkpoint saver persists it with it. Cloning through the same JSON as the checkpoints
 * means a state that would not survive a restart already fails in a normal run, not only after a crash.
 */
@Component
class CaseGraphStateSerializer extends PlainTextStateSerializer {

    private final JsonMapper json;

    CaseGraphStateSerializer(JsonMapper json) {
        super(OverAllState::new);
        this.json = json;
    }

    @Override
    public String contentType() {
        return "application/json";
    }

    @Override
    public void writeData(Map<String, Object> data, ObjectOutput out) throws IOException {
        Serializer.writeUTF(toJson(data), out);
    }

    @Override
    public Map<String, Object> readData(ObjectInput in) throws IOException {
        return fromJson(Serializer.readUTF(in));
    }

    String toJson(Map<String, Object> data) {
        data.keySet().forEach(CaseGraphStateSerializer::typeOf);
        return json.writeValueAsString(data);
    }

    Map<String, Object> fromJson(String string) {
        Map<String, Object> data = new HashMap<>();
        for (Map.Entry<String, JsonNode> property : json.readTree(string).properties()) {
            if (!property.getValue().isNull()) {
                data.put(property.getKey(), json.treeToValue(property.getValue(), typeOf(property.getKey())));
            }
        }
        return data;
    }

    private static Class<?> typeOf(String key) {
        Class<?> type = CaseGraphState.TYPES.get(key);
        if (type == null) {
            throw new IllegalStateException("undeclared graph state key '" + key + "'; add it to CaseGraphState.TYPES");
        }
        return type;
    }
}
