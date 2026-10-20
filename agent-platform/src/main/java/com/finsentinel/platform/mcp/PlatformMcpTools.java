package com.finsentinel.platform.mcp;

import com.finsentinel.common.Alert;
import com.finsentinel.platform.alert.AlertRepository;
import com.finsentinel.platform.audit.AuditEntry;
import com.finsentinel.platform.audit.AuditLog;
import com.finsentinel.platform.budget.RateLimiter;
import com.finsentinel.platform.alert.StoredAlert;
import com.finsentinel.platform.cases.CaseRepository;
import com.finsentinel.platform.cases.CaseStage;
import com.finsentinel.platform.cases.FraudCase;
import com.finsentinel.platform.investigation.CaseReport;
import com.finsentinel.platform.investigation.Review;
import com.finsentinel.platform.knowledge.KnowledgeSearch;
import com.finsentinel.platform.knowledge.RegulationHit;
import com.finsentinel.platform.security.Caller;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the platform offers external agents over MCP (ADR 0002): read-only case lookup and the regulatory knowledge
 * base. Results are compact views, like the tools of our own agents.
 *
 * <p>Case tools have no tenant parameter: the tenant is the caller's, from its access token (ADR 0014), so a model
 * driving the client cannot be talked into another bank's cases. Every case read is filtered by it; a token without a
 * tenant gets no cases at all.
 */
@Component
class PlatformMcpTools {

    static final String SEARCH_LIMIT_KEY = "mcp:searchRegulations";

    private final CaseRepository cases;
    private final AlertRepository alerts;
    private final KnowledgeSearch knowledge;
    private final RateLimiter rateLimiter;
    private final McpServerProperties props;
    private final AuditLog audit;

    PlatformMcpTools(CaseRepository cases, AlertRepository alerts, KnowledgeSearch knowledge, RateLimiter rateLimiter,
                     McpServerProperties props, AuditLog audit) {
        this.audit = audit;
        this.cases = cases;
        this.alerts = alerts;
        this.knowledge = knowledge;
        this.rateLimiter = rateLimiter;
        this.props = props;
    }

    @Tool(description = "List your bank's fraud cases with each one's latest verdict (disposition, risk score). Sorted by "
            + "alert priority, then most recently updated; not by risk score. Stages: ANALYSIS (agents working), "
            + "AWAITING_DECISION (report ready for an analyst), CLOSED, FAILED.")
    List<CaseSummary> listCases(@ToolParam(description = "Only cases in this stage; omit for all", required = false) CaseStage stage,
                                @ToolParam(description = "Most cases to return; large values are capped", required = false) Integer limit) {
        int max = limit == null ? props.maxCases() : Math.clamp(limit, 1, props.maxCases());
        List<CaseSummary> listed = cases.listWithLatestVerdict(Caller.currentTenant(), stage, max).stream()
                .map(l -> CaseSummary.of(l.fraudCase(), l.latestDisposition(), l.latestRiskScore()))
                .toList();
        audit.record(AuditEntry.forCurrentCaller("mcp listCases", null, "OK",
                Map.of("stage", String.valueOf(stage), "returned", String.valueOf(listed.size()))));
        return listed;
    }

    @Tool(description = "One fraud case of your bank: its alerts, the latest analysis report (disposition, risk score, "
            + "findings with transaction ids, recommended actions, regulatory notes citing chunk ids) and the reviewer's "
            + "verdict, and the analyst's decision if any.")
    CaseView getCase(@ToolParam(description = "Case id") String caseId) {
        String tenantId = Caller.currentTenant();
        // Same answer for another bank's case as for one that does not exist.
        Optional<FraudCase> found = cases.find(tenantId, caseId);
        audit.record(AuditEntry.forCurrentCaller("mcp getCase", caseId, found.isPresent() ? "OK" : "NOT_FOUND", Map.of()));
        FraudCase c = found.orElseThrow(() -> new IllegalArgumentException("no case " + caseId));
        List<AlertView> caseAlerts = alerts.findByCase(tenantId, caseId).stream().map(StoredAlert::alert)
                .map(AlertView::of).toList();
        AnalysisView latest = cases.analyses(tenantId, caseId).stream().findFirst().map(AnalysisView::of).orElse(null);
        return new CaseView(CaseSummary.of(c, latest == null ? null : latest.disposition(),
                latest == null ? null : latest.riskScore()), caseAlerts, latest,
                c.decision() == null ? null : c.decision().name(),
                c.decidedBy());
    }

    @Tool(description = "Search MAS regulations and guidelines (E-Payments User Protection Guidelines, Shared "
            + "Responsibility Framework, MAS Notice 626 on AML/CFT, Technology Risk Management Guidelines). One short "
            + "question about one topic per call. Returns passages with their chunk ids and citations.")
    List<RegulationHit> searchRegulations(@ToolParam(description = "One short question, e.g. 'who bears the loss for "
            + "an authorised transfer to a scammer'") String question) {
        if (!rateLimiter.tryAcquire(SEARCH_LIMIT_KEY, props.maxRegulationSearchesPerHour(), Duration.ofHours(1))) {
            throw new IllegalStateException("regulation search budget of " + props.maxRegulationSearchesPerHour()
                    + " per hour is used up; try again later");
        }
        return knowledge.search(question);
    }

    @Tool(description = "Full text of regulation passages by chunk id, e.g. to check the passages a case report cites.")
    List<RegulationHit> readRegulationPassages(@ToolParam(description = "Chunk ids, as cited in a report or returned "
            + "by searchRegulations") List<String> chunkIds) {
        return knowledge.passages(chunkIds.stream().distinct().limit(props.maxPassages()).toList());
    }

    record CaseSummary(String caseId, String accountId, String stage, String priority, String latestDisposition,
                       Integer latestRiskScore, int analysisRun, Instant openedAt, Instant updatedAt) {

        static CaseSummary of(FraudCase c, String latestDisposition, Integer latestRiskScore) {
            return new CaseSummary(c.caseId(), c.accountId(), c.stage().name(), c.priority().name(), latestDisposition,
                    latestRiskScore, c.analysisRun(), c.openedAt(), c.updatedAt());
        }
    }

    record AlertView(String rule, String severity, String reason, Instant eventTime, List<String> txnIds) {

        static AlertView of(Alert a) {
            return new AlertView(a.rule().name(), a.severity().name(), a.reason(), a.eventTime(), a.txnIds());
        }
    }

    record AnalysisView(int run, String mode, String disposition, int riskScore, String summary,
                        List<CaseReport.Finding> findings, List<String> recommendedActions,
                        List<CaseReport.RegulatoryNote> regulatoryNotes, Review review, Instant createdAt) {

        static AnalysisView of(CaseRepository.CaseAnalysis a) {
            CaseReport r = a.report();
            return new AnalysisView(a.run(), a.mode(), r.disposition().name(), r.riskScore(), r.summary(), r.findings(),
                    r.recommendedActions(), r.regulatoryNotes(), a.review(), a.createdAt());
        }
    }

    record CaseView(CaseSummary caseSummary, List<AlertView> alerts, AnalysisView latestAnalysis, String decision,
                    String decidedBy) {
    }
}
