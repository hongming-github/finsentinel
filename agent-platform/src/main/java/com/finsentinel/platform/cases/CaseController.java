package com.finsentinel.platform.cases;

import com.finsentinel.platform.alert.AlertProperties;
import com.finsentinel.platform.alert.AlertRepository;
import com.finsentinel.platform.alert.StoredAlert;
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

/**
 * Analyst-facing case API. Tenant comes from the path and the analyst from {@code X-Analyst-Id} until Keycloak
 * (phase 6) supplies both from the token.
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
                                cases.events(tenantId, caseId))))
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
                             @RequestHeader(name = "X-Analyst-Id", required = false) String analyst,
                             @RequestBody DecisionRequest request) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED)
                    .body(Map.of("error", "If-Match with the reviewed case version is required"));
        }
        if (analyst == null || analyst.isBlank() || request.decision() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "X-Analyst-Id and decision are required"));
        }
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

    record DecisionRequest(Decision decision, String note) {
    }

    record CaseDetail(FraudCase fraudCase, List<StoredAlert> alerts, List<CaseRepository.CaseAnalysis> analyses,
                      List<CaseEvent> events) {
    }
}
