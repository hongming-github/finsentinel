package com.finsentinel.platform.investigation;

import com.finsentinel.common.AlertRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Express step: writes the case report from the specialists' findings, without tools. The same validation and
 * bounded repair as the single agent, grounded in what the specialists' tools actually returned.
 */
@Component
class LlmReportWriter implements ReportWriter {

    private static final Logger log = LoggerFactory.getLogger(LlmReportWriter.class);

    private final ChatClient chatClient;
    private final JsonMapper json;
    private final InvestigationProperties.Report rules;
    private final String systemPrompt;
    private final Map<AlertRule, String> playbooks;

    LlmReportWriter(ChatClient.Builder chatClientBuilder, JsonMapper json, InvestigationProperties props,
                    @Value("classpath:prompts/report-writer.md") Resource systemPrompt) {
        this.chatClient = chatClientBuilder.build();
        this.json = json;
        this.rules = props.report();
        this.systemPrompt = ReportWriting.read(systemPrompt);
        this.playbooks = ReportWriting.loadPlaybooks();
    }

    @Override
    public InvestigationResult write(CaseContext caseContext, AnalysisPlan plan, List<SpecialistReport> reports,
                                     Revision revision) {
        Set<String> seenTxnIds = new HashSet<>();
        Set<String> seenChunkIds = new HashSet<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (SpecialistReport r : reports) {
            seenTxnIds.addAll(r.seenTxnIds());
            seenChunkIds.addAll(r.seenChunkIds());
            toolCalls.addAll(r.toolCalls());
        }
        String brief = "Write the report for this case. " + ReportWriting.caseBrief(caseContext, playbooks, json)
                + "\n\nSpecialist reports:\n" + json.writerWithDefaultPrettyPrinter().writeValueAsString(
                reports.stream().map(SpecialistView::of).toList())
                + ReportWriting.revisionNote(revision, json);

        StructuredOutput.Answer<CaseReport> draft = ask(brief);
        LlmUsage usage = draft.usage();
        List<String> problems = CaseReportValidator.problems(draft.value(), seenTxnIds, seenChunkIds,
                plan.involvesTransfer(), rules);
        List<String> repaired = new ArrayList<>();
        for (int repair = 0; !problems.isEmpty() && repair < rules.maxRepairs(); repair++) {
            repaired.addAll(problems);
            draft = ask(ReportWriting.repairPrompt(brief, draft.value(), problems, json, ReportWriting.FIX_WITHOUT_TOOLS));
            usage = usage.plus(draft.usage());
            problems = CaseReportValidator.problems(draft.value(), seenTxnIds, seenChunkIds, plan.involvesTransfer(),
                    rules);
        }
        if (!problems.isEmpty()) {
            Set<String> unseen = new HashSet<>(CaseReportValidator.ungrounded(draft.value(), seenTxnIds));
            log.warn("Case {}: report still invalid after repair; unseen ids {} of which alert transactions {}",
                    caseContext.caseId(), unseen, unseen.stream().filter(caseContext.txnIds()::contains).toList());
            throw new InvalidReportException(problems);
        }
        // The reviewer checks this report against the specialists' reports, which is what the writer saw.
        return InvestigationResult.of(draft.value(), List.copyOf(toolCalls), List.copyOf(repaired), usage, List.of());
    }

    private StructuredOutput.Answer<CaseReport> ask(String userMessage) {
        return StructuredOutput.ask(chatClient, systemPrompt, userMessage, List.of(), CaseReport.class);
    }

    /** What the writer sees of a specialist's report: findings, not bookkeeping. */
    record SpecialistView(Specialist specialist, String summary, List<CaseReport.Finding> findings,
                          List<CaseReport.RegulatoryNote> regulatoryNotes) {

        static SpecialistView of(SpecialistReport r) {
            return new SpecialistView(r.specialist(), r.summary(), r.findings(), r.regulatoryNotes());
        }
    }
}
