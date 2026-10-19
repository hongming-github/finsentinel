package com.finsentinel.platform.systems;

import com.finsentinel.platform.resilience.CircuitBreaker;
import com.finsentinel.platform.security.ServiceToken;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Map;

/** {@link ExternalSystems} over the two MCP servers, {@code mcp-banking} and {@code mcp-compliance}. */
@Component
class McpExternalSystems implements ExternalSystems, AutoCloseable {

    private final McpServerConnection banking;
    private final McpServerConnection compliance;

    McpExternalSystems(SystemsProperties props, JsonMapper json, ServiceToken token, Tracer tracer) {
        this.banking = new McpServerConnection("banking", props.bankingUrl(), props.connectTimeout(),
                props.requestTimeout(), json, token::bearer, tracer, breaker(props));
        this.compliance = new McpServerConnection("compliance", props.complianceUrl(), props.connectTimeout(),
                props.requestTimeout(), json, token::bearer, tracer, breaker(props));
    }

    /** One per system: CRM and core banking failing says nothing about the screening vendor. */
    private static CircuitBreaker breaker(SystemsProperties props) {
        return new CircuitBreaker(props.circuit().failureThreshold(), props.circuit().openFor(), Clock.systemUTC());
    }

    @Override
    public CustomerProfile customerProfile(String tenantId, String customerId) {
        return banking.call("getCustomerProfile", Map.of("tenantId", tenantId, "customerId", customerId),
                CustomerProfile.class);
    }

    @Override
    public AccountDetails accountDetails(String tenantId, String accountId) {
        return banking.call("getAccountDetails", Map.of("tenantId", tenantId, "accountId", accountId),
                AccountDetails.class);
    }

    @Override
    public ScreeningResult screenCounterparty(String tenantId, String counterpartyId) {
        return compliance.call("screenCounterparty", Map.of("tenantId", tenantId, "counterpartyId", counterpartyId),
                ScreeningResult.class);
    }

    @Override
    public void close() {
        banking.close();
        compliance.close();
    }
}
