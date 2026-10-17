package com.finsentinel.platform.cases;

import com.finsentinel.platform.alert.AlertProperties;
import com.finsentinel.platform.alert.AlertRepository;
import com.finsentinel.platform.alert.StoredAlert;
import com.finsentinel.platform.observability.Traces;
import com.finsentinel.platform.security.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Analyst-facing case API. The path names the tenant; {@code TenantPathFilter} has already checked it is the caller's
 * own, and the deciding analyst is the token's user, never a header the client could set.
 */
@RestController
@RequestMapping("/api/tenants/{tenantId}/cases")
class CaseController {

    private final CaseRepository cases;
    private final AlertRepository alerts;
    private final AlertProperties props;

    CaseController(CaseRepository cases, AlertRepository alerts, AlertProperties props) {
        this.cases = cases;
        this.alerts = alerts;
        this.props = props;
    }

    @GetMapping
    List<FraudCase> list(@PathVariable String tenantId, @RequestParam(required = false) CaseStage stage,
                         @RequestParam(defaultValue = "50") int limit) {
        return cases.list(tenantId, stage, Math.clamp(limit, 1, props.maxPageSize()));
    }

    @GetMapping("/{caseId}")
    ResponseEntity<CaseDetail> detail(@PathVariable String tenantId, @PathVariable String caseId) {
        return cases.find(tenantId, caseId)
                .map(c -> ResponseEntity.ok()
                        .eTag("\"" + c.version() + "\"")
                        .body(new CaseDetail(c, alerts.findByCase(tenantId, caseId), cases.analyses(tenantId, caseId),
                                cases.events(tenantId, caseId), traces(cases.runTrace(tenantId, caseId)))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Records the analyst's decision. {@code If-Match} must carry the version the analyst reviewed (the ETag of the
     * detail response): if anything changed since, for example a new alert joined the case, the decision is refused
     * with 409 and the current case, so nobody closes a case on evidence they have not seen.
     */
    @PostMapping("/{caseId}/decision")
    ResponseEntity<?> decide(@PathVariable String tenantId, @PathVariable String caseId,
                             @RequestHeader(name = "If-Match", required = false) String ifMatch,
                             @RequestBody DecisionRequest request) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED)
                    .body(Map.of("error", "If-Match with the reviewed case version is required"));
        }
        if (request.decision() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "decision is required"));
        }
        String analyst = Caller.current().map(Caller::username).orElseThrow();
        if (request.decision() == Decision.REQUEST_REANALYSIS && (request.note() == null || request.note().isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("error", "a note telling the agents what to look at is required"));
        }
        int version;
        try {
            version = Integer.parseInt(ifMatch.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "If-Match must be a case version"));
        }
        return switch (cases.decide(tenantId, caseId, version, request.decision(), analyst, request.note())) {
            case CaseRepository.DecisionResult.Applied applied -> ResponseEntity.ok()
                    .eTag("\"" + applied.fraudCase().version() + "\"").body(applied.fraudCase());
            case CaseRepository.DecisionResult.Conflict conflict -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .eTag("\"" + conflict.current().version() + "\"").body(conflict.current());
            case CaseRepository.DecisionResult.NotFound notFound -> ResponseEntity.notFound().build();
        };
    }

    /** Trace ids only (no span ids or flags): what a link into Tempo needs. */
    private static CaseTraces traces(CaseRepository.RunTrace run) {
        return new CaseTraces(Traces.traceId(run.parent()),
                run.alerts().stream().map(Traces::traceId).filter(Objects::nonNull).distinct().toList());
    }

    record DecisionRequest(Decision decision, String note) {
    }

    record CaseDetail(FraudCase fraudCase, List<StoredAlert> alerts, List<CaseRepository.CaseAnalysis> analyses,
                      List<CaseEvent> events, CaseTraces traces) {
    }

    /**
     * Where to look the case up in the trace backend (ADR 0020): the trace the current analysis run continues and
     * the traces its alerts arrived in, oldest first. Null and empty when nothing was traced.
     */
    record CaseTraces(String currentRun, List<String> alerts) {
    }
}
