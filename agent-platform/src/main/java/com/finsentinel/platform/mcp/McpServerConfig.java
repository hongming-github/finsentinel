package com.finsentinel.platform.mcp;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

@Configuration(proxyBeanMethods = false)
class McpServerConfig {

    /**
     * The platform's MCP tools, registered with the MCP server only. Not as a {@code ToolCallbackProvider} bean:
     * Spring AI's tool resolver collects every such bean, which would put these tenant-parameterised tools where our
     * agents' tool calling looks them up. (Spring AI 2.0.1 does not execute resolver-only tools by default, but the
     * server's tools have no business there.)
     */
    @Bean
    List<McpStatelessServerFeatures.SyncToolSpecification> platformMcpToolSpecifications(PlatformMcpTools tools) {
        return Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks())
                .map(callback -> McpToolUtils.toStatelessSyncToolSpecification(callback, null))
                .toList();
    }
}
