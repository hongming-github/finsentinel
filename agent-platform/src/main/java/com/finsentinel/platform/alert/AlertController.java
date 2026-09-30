package com.finsentinel.platform.alert;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Tenant comes from the path for now. Once Keycloak is in, it will come from the token and the
 * path segment will be checked against it.
 */
@RestController
@RequestMapping("/api/tenants/{tenantId}/alerts")
class AlertController {

    private static final int MAX_LIMIT = 200;

    private final AlertRepository alerts;

    AlertController(AlertRepository alerts) {
        this.alerts = alerts;
    }

    @GetMapping
    List<StoredAlert> recent(@PathVariable String tenantId, @RequestParam(defaultValue = "50") int limit) {
        return alerts.findRecent(tenantId, Math.clamp(limit, 1, MAX_LIMIT));
    }
}
