package com.finsentinel.platform.investigation;

import com.finsentinel.common.AlertRule;
import org.springframework.ai.chat.client.ChatClient;
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

    private final ChatClient chatClient;
    private final InvestigationToolsFactory toolsFactory;
    private final JsonMapper json;
    private final InvestigationProperties.Report rules;
    private final String systemPrompt;
    private final Map<AlertRule, String> playbooks;

    LlmCaseInvestigator(ChatClient.Builder chatClientBuilder, InvestigationToolsFactory toolsFactory, JsonMapper json,
                        InvestigationProperties props,
                        @Value("classpath:prompts/investigator-system.md") Resource systemPrompt) {
        this.chatClient = chatClientBuilder.build();
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

        StructuredOutput.Answer<CaseReport> draft = ask(callbacks, brief);
        LlmUsage usage = draft.usage();
        boolean involvesTransfer = tools.involvesTransfer();
        List<String> problems = CaseReportValidator.problems(draft.value(), tools.seenTxnIds(), tools.seenChunkIds(),
                involvesTransfer, rules);
        List<String> repaired = new ArrayList<>();
        for (int repair = 0; !problems.isEmpty() && repair < rules.maxRepairs(); repair++) {
            repaired.addAll(problems);
            draft = ask(callbacks, ReportWriting.repairPrompt(brief, draft.value(), problems, json,
                    ReportWriting.FIX_WITH_TOOLS));
            usage = usage.plus(draft.usage());
            problems = CaseReportValidator.problems(draft.value(), tools.seenTxnIds(), tools.seenChunkIds(),
                    involvesTransfer, rules);
        }
        if (!problems.isEmpty()) {
            throw new InvalidReportException(problems);
        }
        return InvestigationResult.of(draft.value(), tools.calls(), List.copyOf(repaired), usage, tools.evidence());
    }

    /**
     * The case's alerts, the playbook of each rule that fired (and only those), and the analyst's note when the case
     * was sent back for another look.
     */
    private String brief(CaseContext caseContext) {
        return "Investigate this case. " + ReportWriting.caseBrief(caseContext, playbooks, json);
    }

    private StructuredOutput.Answer<CaseReport> ask(List<ToolCallback> tools, String userMessage) {
        return StructuredOutput.ask(chatClient, systemPrompt, userMessage, tools, CaseReport.class);
    }
}
