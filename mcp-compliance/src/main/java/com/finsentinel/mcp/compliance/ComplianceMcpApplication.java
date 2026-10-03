package com.finsentinel.mcp.compliance;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/** MCP server in front of third-party compliance systems: watchlist screening (ADR 0002). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ComplianceMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(ComplianceMcpApplication.class, args);
    }

    @Bean
    ToolCallbackProvider complianceToolCallbacks(ComplianceTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
