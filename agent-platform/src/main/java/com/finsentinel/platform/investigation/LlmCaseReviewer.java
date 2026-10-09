package com.finsentinel.platform.investigation;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.finsentinel.common.AlertRule;
import com.finsentinel.platform.llm.AgentRole;
import com.finsentinel.platform.llm.LlmGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * The Review step: a second model reads the report against the evidence and the playbooks, the four-eyes check a
 * compliance team applies before a case reaches an analyst. It can send the report back; the graph bounds how often.
 */
@Component
class LlmCaseReviewer implements CaseReviewer {

    private final AgentModels models;
    private final JsonMapper json;
    private final String systemPrompt;
    private final Map<AlertRule, String> playbooks;

    LlmCaseReviewer(AgentModels models, JsonMapper json,
                    @Value("classpath:prompts/reviewer.md") Resource systemPrompt) {
        this.models = models;
        this.json = json;
        this.systemPrompt = ReportWriting.read(systemPrompt);
        this.playbooks = ReportWriting.loadPlaybooks();
    }

    @Override
    public Review review(CaseContext caseContext, InvestigationResult investigation, List<SpecialistReport> evidence) {
        // The reviewer sees exactly what the author saw: without it, it flags supported claims as unsupported and the
        // writer deletes correct findings (measured in the first live comparison).
        String evidenceText = evidence.isEmpty()
                ? "Evidence: every tool call the author made and the exact result it got back:\n"
                + json.writerWithDefaultPrettyPrinter().writeValueAsString(investigation.evidence())
                : "Evidence: the specialist reports the author wrote from:\n" + json.writerWithDefaultPrettyPrinter()
                .writeValueAsString(evidence.stream().map(LlmReportWriter.SpecialistView::of).toList());
        String user = "Review this case report." + ReportWriting.caseBrief(caseContext, playbooks, json)
                + "\n\n" + evidenceText
                + "\n\nReport:\n" + json.writerWithDefaultPrettyPrinter().writeValueAsString(investigation.report());
        LlmGateway.Answer<Verdict> answer = models.ask(AgentRole.REVIEWER, caseContext, systemPrompt, user, List.of(),
                Verdict.class);
        Verdict verdict = answer.value();
        if (verdict.approved()) {
            return new Review(true, List.of(), answer.usage());
        }
        // A rejection the writer cannot act on is still recorded as one.
        List<String> issues = verdict.issues().isEmpty()
                ? List.of("The reviewer rejected the report without a reason: re-check that every alert signal is addressed")
                : verdict.issues();
        return new Review(false, issues, answer.usage());
    }

    /** What the reviewer model must return. */
    record Verdict(
            @JsonPropertyDescription("true when the report can go to an analyst as it is")
            boolean approved,
            @JsonPropertyDescription("When not approved: each material problem, as an instruction to the report writer. "
                    + "Empty when approved")
            List<String> issues) {
    }
}
