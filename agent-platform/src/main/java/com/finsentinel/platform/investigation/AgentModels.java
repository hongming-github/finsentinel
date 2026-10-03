package com.finsentinel.platform.investigation;

import com.finsentinel.platform.llm.AgentRole;
import com.finsentinel.platform.llm.LlmPricing;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/** How every agent calls a model: the router picks the model for its role and case, the pricing turns usage into cost. */
@Component
class AgentModels {

    private final ChatClient chatClient;
    private final ModelRouter router;
    private final LlmPricing pricing;

    AgentModels(ChatClient.Builder chatClientBuilder, ModelRouter router, LlmPricing pricing) {
        this.chatClient = chatClientBuilder.build();
        this.router = router;
        this.pricing = pricing;
    }

    <T> StructuredOutput.Answer<T> ask(AgentRole role, CaseContext caseContext, String system, String user,
                                       List<ToolCallback> tools, Class<T> type) {
        return StructuredOutput.ask(chatClient, router.modelFor(role, caseContext), system, user, tools, type, pricing);
    }
}
