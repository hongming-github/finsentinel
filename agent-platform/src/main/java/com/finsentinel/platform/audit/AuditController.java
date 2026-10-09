package com.finsentinel.platform.audit;

import com.finsentinel.platform.alert.AlertProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** A bank's access log, for its supervisors (ADR 0016). Reading it is itself recorded. */
@RestController
@RequestMapping("/api/tenants/{tenantId}/audit")
class AuditController {

    private final AuditLog audit;
    private final AlertProperties props;

    AuditController(AuditLog audit, AlertProperties props) {
        this.audit = audit;
        this.props = props;
    }

    @GetMapping
    List<AuditLog.StoredEntry> recent(@PathVariable String tenantId, @RequestParam(defaultValue = "100") int limit) {
        return audit.recent(tenantId, Math.clamp(limit, 1, props.maxPageSize()));
    }

    /** Recomputes the bank's hash chain: whether any entry was changed, removed or reordered, and where. */
    @GetMapping("/verify")
    AuditLog.Verification verify(@PathVariable String tenantId) {
        return audit.verify(tenantId);
    }
}
