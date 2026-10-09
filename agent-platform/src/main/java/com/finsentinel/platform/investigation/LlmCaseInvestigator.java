package com.finsentinel.platform.investigation;

import com.finsentinel.common.AlertRule;
import com.finsentinel.platform.llm.AgentRole;
import com.finsentinel.platform.llm.LlmGateway;
import com.finsentinel.platform.llm.LlmUsage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Single-agent investigator: a ChatClient call with all the tools and a schema-constrained answer, then validation.
 * The baseline the multi-agent analysis is measured against.
 * A rejected draft gets up to {@code report.max-repairs} more calls with the problems spelled out; if it is
 * still invalid the investigation fails and the queue retries or marks it FAILED for a human.
 */
@Component
class LlmCaseInvestigator implements CaseInvestigator {

    private final AgentModels models;
    private final InvestigationToolsFactory toolsFactory;
    private final JsonMapper json;
    private final InvestigationProperties.Report rules;
    private final String systemPrompt;
    private final Map<AlertRule, String> playbooks;

    LlmCaseInvestigator(AgentModels models, InvestigationToolsFactory toolsFactory, JsonMapper json,
                        InvestigationProperties props,
                        @Value("classpath:prompts/investigator-system.md") Resource systemPrompt) {
        this.models = models;
        this.toolsFactory = toolsFactory;
        this.json = json;
        this.rules = props.report();
        this.systemPrompt = ReportWriting.read(systemPrompt);
        this.playbooks = ReportWriting.loadPlaybooks();
    }

    @Override
    public InvestigationResult investigate(CaseContext caseContext, Revision revision) {
        InvestigationTools tools = toolsFactory.forCase(caseContext);
        List<ToolCallback> callbacks = tools.callbacks();
        String brief = brief(caseContext) + ReportWriting.revisionNote(revision, json);

        LlmGateway.Answer<CaseReport> draft = ask(caseContext, callbacks, brief);
        LlmUsage usage = draft.usage();
        boolean involvesTransfer = tools.involvesTransfer();
        List<String> problems = CaseReportValidator.problems(draft.value(), tools.seenTxnIds(), tools.seenChunkIds(),
                involvesTransfer, tools.untrustedInstructions(), rules);
        List<String> repaired = new ArrayList<>();
        for (int repair = 0; !problems.isEmpty() && repair < rules.maxRepairs(); repair++) {
            repaired.addAll(problems);
            draft = ask(caseContext, callbacks, ReportWriting.repairPrompt(brief, draft.value(), problems, json,
                    ReportWriting.FIX_WITH_TOOLS));
            usage = usage.plus(draft.usage());
            problems = CaseReportValidator.problems(draft.value(), tools.seenTxnIds(), tools.seenChunkIds(),
                    involvesTransfer, tools.untrustedInstructions(), rules);
        }
        if (!problems.isEmpty()) {
            throw new InvalidReportException(problems);
        }
        // Reranking inside regulation searches is part of what this investigation cost.
        return InvestigationResult.of(draft.value(), tools.calls(), List.copyOf(repaired), usage.plus(tools.toolUsage()),
                tools.evidence(), tools.untrustedInstructions());
    }

    /** See {@link ReportWriting#caseBrief}: playbooks, then alerts, then the analyst's note. */
    private String brief(CaseContext caseContext) {
        return "Investigate this case." + ReportWriting.caseBrief(caseContext, playbooks, json);
    }

    private LlmGateway.Answer<CaseReport> ask(CaseContext caseContext, List<ToolCallback> tools, String userMessage) {
        return models.ask(AgentRole.INVESTIGATOR, caseContext, systemPrompt, userMessage, tools, CaseReport.class);
    }
}
