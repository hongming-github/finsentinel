package com.finsentinel.platform.systems;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpServerConnectionTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void textContentIsReadAsTheExpectedRecord() {
        ScreeningResult result = McpServerConnection.parse("compliance", "screenCounterparty",
                result("{\"counterpartyId\":\"p-1\",\"hits\":[{\"list\":\"MULE_ACCOUNTS\",\"reason\":\"r\","
                        + "\"listedOn\":\"2026-09-12\"}],\"extra\":\"ignored\"}", false), ScreeningResult.class, json);

        assertThat(result.hits()).extracting(ScreeningResult.Hit::list).containsExactly("MULE_ACCOUNTS");
    }

    @Test
    void toolErrorBecomesSystemUnavailable() {
        assertThatThrownBy(() -> McpServerConnection.parse("banking", "getCustomerProfile",
                result("503 Service Unavailable", true), CustomerProfile.class, json))
                .isInstanceOf(SystemUnavailableException.class)
                .hasMessage("banking unavailable: getCustomerProfile failed: 503 Service Unavailable");
    }

    @Test
    void unreadableAnswerBecomesSystemUnavailable() {
        assertThatThrownBy(() -> McpServerConnection.parse("banking", "getCustomerProfile",
                result("<html>gateway</html>", false), CustomerProfile.class, json))
                .isInstanceOf(SystemUnavailableException.class)
                .hasMessageContaining("unreadable content");
    }

    private static McpSchema.CallToolResult result(String text, boolean error) {
        return McpSchema.CallToolResult.builder().content(List.of(new McpSchema.TextContent(text))).isError(error).build();
    }
}
