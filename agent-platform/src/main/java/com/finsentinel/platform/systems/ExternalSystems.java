package com.finsentinel.platform.systems;

/**
 * Systems the platform does not own (ADR 0002): the bank's CRM and core banking, and the screening vendor. Reached
 * over MCP in production; tests swap in a fake. Callers pass the tenant and the customer, account or counterparty;
 * agents never do, their tools fix them per case.
 *
 * <p>Every method throws {@link SystemUnavailableException} when the system cannot answer; the investigation goes on
 * without it.
 */
public interface ExternalSystems {

    CustomerProfile customerProfile(String tenantId, String customerId);

    AccountDetails accountDetails(String tenantId, String accountId);

    ScreeningResult screenCounterparty(String tenantId, String counterpartyId);
}
