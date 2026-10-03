package com.finsentinel.mcp.banking;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * MCP server in front of the bank's own systems (CRM, core banking). In a real bank another team owns these
 * systems and this server; the agent platform only sees the MCP tools (ADR 0002).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BankingMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankingMcpApplication.class, args);
    }

    @Bean
    ToolCallbackProvider bankingToolCallbacks(BankingTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
