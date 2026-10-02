package com.finsentinel.platform.investigation;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CaseReportController {

    private final InvestigationRepository investigations;

    CaseReportController(InvestigationRepository investigations) {
        this.investigations = investigations;
    }

    @GetMapping("/api/tenants/{tenantId}/alerts/{alertId}/report")
    ResponseEntity<InvestigationRepository.CaseReportView> report(@PathVariable String tenantId,
                                                                  @PathVariable String alertId) {
        return ResponseEntity.of(investigations.findReport(tenantId, alertId));
    }
}
