package com.finsentinel.platform.investigation;

import com.finsentinel.common.AlertRule;
import org.springframework.ai.chat.client.ChatClient;
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

    private final ChatClient chatClient;
    private final InvestigationToolsFactory toolsFactory;
    private final JsonMapper json;
    private final Map<Specialist, String> prompts = new EnumMap<>(Specialist.class);
    private final Map<AlertRule, String> playbooks;

    LlmSpecialistAgents(ChatClient.Builder chatClientBuilder, InvestigationToolsFactory toolsFactory, JsonMapper json) {
        this.chatClient = chatClientBuilder.build();
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
        StructuredOutput.Answer<SpecialistFindings> answer = StructuredOutput.ask(chatClient, prompts.get(specialist),
                "Examine this case. " + ReportWriting.caseBrief(caseContext, playbooks, json),
                tools.callbacks(specialist.tools()), SpecialistFindings.class);
        SpecialistFindings found = answer.value();
        List<CaseReport.RegulatoryNote> notes = specialist == Specialist.REGULATIONS ? found.regulatoryNotes() : List.of();
        return new SpecialistReport(specialist, false, found.summary(), found.findings(), notes, tools.calls(),
                tools.seenTxnIds(), tools.seenChunkIds(), answer.usage());
    }
}
