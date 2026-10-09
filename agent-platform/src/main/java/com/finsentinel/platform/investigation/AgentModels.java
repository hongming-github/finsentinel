package com.finsentinel.platform.investigation;

import com.finsentinel.platform.privacy.PiiMasker;
import com.finsentinel.platform.llm.AgentRole;
import com.finsentinel.platform.llm.LlmGateway;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * How every agent calls a model: the router picks the model for its role and case, the gateway calls and prices it.
 * The case material in the user message (alerts, analyst note, specialist reports, evidence) is pseudonymised first;
 * tool results are pseudonymised where they are produced ({@link InvestigationTools}). System prompts are our own text.
 */
@Component
class AgentModels {

    private final LlmGateway gateway;
    private final ModelRouter router;
    private final PiiMasker masker;

    AgentModels(LlmGateway gateway, ModelRouter router, PiiMasker masker) {
        this.gateway = gateway;
        this.router = router;
        this.masker = masker;
    }

    <T> LlmGateway.Answer<T> ask(AgentRole role, CaseContext caseContext, String system, String user,
                                 List<ToolCallback> tools, Class<T> type) {
        return gateway.ask(router.modelFor(role, caseContext), system, masker.mask(user, caseContext.caseId()), tools,
                type);
    }
}
