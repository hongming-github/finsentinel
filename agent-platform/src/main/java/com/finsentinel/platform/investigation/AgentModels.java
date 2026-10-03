package com.finsentinel.platform.investigation;

import com.finsentinel.platform.llm.AgentRole;
import com.finsentinel.platform.llm.LlmGateway;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/** How every agent calls a model: the router picks the model for its role and case, the gateway calls and prices it. */
@Component
class AgentModels {

    private final LlmGateway gateway;
    private final ModelRouter router;

    AgentModels(LlmGateway gateway, ModelRouter router) {
        this.gateway = gateway;
        this.router = router;
    }

    <T> LlmGateway.Answer<T> ask(AgentRole role, CaseContext caseContext, String system, String user,
                                 List<ToolCallback> tools, Class<T> type) {
        return gateway.ask(router.modelFor(role, caseContext), system, user, tools, type);
    }
}
