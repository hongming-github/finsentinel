package com.finsentinel.platform.investigation;

import com.finsentinel.common.AlertRule;
import com.finsentinel.platform.llm.AgentRole;
import com.finsentinel.platform.llm.LlmGateway;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The Execute step: one model call per specialist, with that specialist's tools only and its own instructions. A
 * specialist reports facts; the disposition is the report writer's call.
 */
@Component
class LlmSpecialistAgents implements SpecialistAgents {

    private final AgentModels models;
    private final InvestigationToolsFactory toolsFactory;
    private final JsonMapper json;
    private final Map<Specialist, String> prompts = new EnumMap<>(Specialist.class);
    private final Map<AlertRule, String> playbooks;

    LlmSpecialistAgents(AgentModels models, InvestigationToolsFactory toolsFactory, JsonMapper json) {
        this.models = models;
        this.toolsFactory = toolsFactory;
        this.json = json;
        for (Specialist specialist : Specialist.values()) {
            prompts.put(specialist, ReportWriting.read(new ClassPathResource(specialist.prompt())));
        }
        this.playbooks = ReportWriting.loadPlaybooks();
    }

    @Override
    public SpecialistReport run(Specialist specialist, CaseContext caseContext) {
        InvestigationTools tools = toolsFactory.forCase(caseContext);
        LlmGateway.Answer<SpecialistFindings> answer = models.ask(AgentRole.SPECIALIST, caseContext,
                ReportWriting.systemPrompt(prompts.get(specialist), caseContext, playbooks),
                "Examine this case." + ReportWriting.caseData(caseContext, json),
                tools.callbacks(specialist.tools()), SpecialistFindings.class);
        SpecialistFindings found = answer.value();
        List<CaseReport.RegulatoryNote> notes = specialist == Specialist.REGULATIONS ? found.regulatoryNotes() : List.of();
        return new SpecialistReport(specialist, false, found.summary(), found.findings(), notes, tools.calls(),
                tools.seenTxnIds(), tools.seenChunkIds(), answer.usage().plus(tools.toolUsage()),
                tools.untrustedInstructions());
    }
}
