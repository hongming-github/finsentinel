package com.finsentinel.platform.investigation;

import com.finsentinel.common.AlertRule;
import com.finsentinel.platform.llm.AgentRole;
import com.finsentinel.platform.llm.LlmGateway;
import com.finsentinel.platform.llm.LlmUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.EnumMap;
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

    private final AgentModels models;
    private final JsonMapper json;
    private final InvestigationProperties.Report rules;
    private final String systemPrompt;
    private final Map<AlertRule, String> playbooks;

    LlmReportWriter(AgentModels models, JsonMapper json, InvestigationProperties props,
                    @Value("classpath:prompts/report-writer.md") Resource systemPrompt) {
        this.models = models;
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

        LlmGateway.Answer<CaseReport> draft = ask(caseContext, brief);
        LlmUsage usage = draft.usage();
        List<String> problems = CaseReportValidator.problems(draft.value(), seenTxnIds, seenChunkIds,
                plan.involvesTransfer(), rules);
        List<String> repaired = new ArrayList<>();
        for (int repair = 0; !problems.isEmpty() && repair < rules.maxRepairs(); repair++) {
            repaired.addAll(problems);
            draft = ask(caseContext, ReportWriting.repairPrompt(brief, draft.value(), problems, json,
                    ReportWriting.FIX_WITHOUT_TOOLS + allowedIds(draft.value(), seenTxnIds)));
            usage = usage.plus(draft.usage());
            problems = CaseReportValidator.problems(draft.value(), seenTxnIds, seenChunkIds, plan.involvesTransfer(),
                    rules);
        }
        if (!problems.isEmpty()) {
            Set<String> unseen = new HashSet<>(CaseReportValidator.ungrounded(draft.value(), seenTxnIds));
            log.warn("Case {}: report still invalid after repair; unseen ids {}, of which alert transactions {}, "
                            + "cited by specialists {}", caseContext.caseId(), unseen,
                    unseen.stream().filter(caseContext.txnIds()::contains).toList(), citedBy(unseen, reports));
            throw new InvalidReportException(problems);
        }
        // The reviewer checks this report against the specialists' reports, which is what the writer saw.
        return InvestigationResult.of(draft.value(), List.copyOf(toolCalls), List.copyOf(repaired), usage, List.of());
    }

    private LlmGateway.Answer<CaseReport> ask(CaseContext caseContext, String userMessage) {
        return models.ask(AgentRole.WRITER, caseContext, systemPrompt, userMessage, List.of(), CaseReport.class);
    }

    /**
     * "Use only ids from the specialist reports" was not enough: twice in live runs a writer kept citing an id no tool
     * had returned. When ids are the problem, the repair names the ids that may be cited.
     */
    private static String allowedIds(CaseReport draft, Set<String> seenTxnIds) {
        if (CaseReportValidator.ungrounded(draft, seenTxnIds).isEmpty()) {
            return "";
        }
        return " The only transaction ids you may cite are: " + seenTxnIds.stream().sorted().toList() + ".";
    }

    /** Which specialists put these ids in their findings, to tell an invented id from a copied one. */
    private static Map<Specialist, List<String>> citedBy(Set<String> ids, List<SpecialistReport> reports) {
        Map<Specialist, List<String>> out = new EnumMap<>(Specialist.class);
        for (SpecialistReport r : reports) {
            List<String> cited = r.findings().stream().flatMap(f -> f.evidenceTxnIds().stream())
                    .filter(ids::contains).distinct().toList();
            if (!cited.isEmpty()) {
                out.put(r.specialist(), cited);
            }
        }
        return out;
    }

    /** What the writer sees of a specialist's report: findings, not bookkeeping. */
    record SpecialistView(Specialist specialist, String summary, List<CaseReport.Finding> findings,
                          List<CaseReport.RegulatoryNote> regulatoryNotes) {

        static SpecialistView of(SpecialistReport r) {
            return new SpecialistView(r.specialist(), r.summary(), r.findings(), r.regulatoryNotes());
        }
    }
}
