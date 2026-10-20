package com.finsentinel.platform.investigation;

import com.finsentinel.platform.privacy.PiiMasker;
import com.finsentinel.platform.llm.AgentRole;
import com.finsentinel.platform.llm.LlmGateway;
import com.finsentinel.platform.llm.LlmMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * How every agent calls a model: the router picks the model for its role and case, the gateway calls and prices it.
 * The case material in the user message (alerts, analyst note, specialist reports, evidence) is pseudonymised first;
 * tool results are pseudonymised where they are produced ({@link InvestigationTools}). System prompts are our own text.
 * The case assistant (ADR 0028) calls with a whole conversation: every message is pseudonymised, the model's earlier
 * answers too (they repeat case material), in the case's scope, so a number has the same pseudonym in every turn.
 */
@Component
public class AgentModels {

    private final LlmGateway gateway;
    private final ModelRouter router;
    private final PiiMasker masker;

    public AgentModels(LlmGateway gateway, ModelRouter router, PiiMasker masker) {
        this.gateway = gateway;
        this.router = router;
        this.masker = masker;
    }

    <T> LlmGateway.Answer<T> ask(AgentRole role, CaseContext caseContext, String system, String user,
                                 List<ToolCallback> tools, Class<T> type) {
        return gateway.ask(router.modelFor(role, caseContext), system, masker.mask(user, caseContext.caseId()), tools,
                type);
    }

    /** A call without tools over several messages, the last one the user's. */
    public <T> LlmGateway.Answer<T> ask(AgentRole role, CaseContext caseContext, String system,
                                        List<LlmMessage> messages, Class<T> type) {
        List<LlmMessage> masked = messages.stream()
                .map(m -> new LlmMessage(m.role(), masker.mask(m.text(), caseContext.caseId()))).toList();
        return gateway.ask(router.modelFor(role, caseContext), system, masked, List.of(), type);
    }
}
