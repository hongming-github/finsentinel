package com.finsentinel.mcp.banking;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The tools this server offers. Each is a thin, read-only call to one system. Ids go into the backend URL as encoded
 * path variables, never concatenated, so an id cannot redirect the request.
 *
 * <p>The tools take the tenant and the customer or account as parameters: this server serves any caller. Restricting
 * an agent to its own case is the caller's job (the platform fixes both per case and never lets the model choose).
 */
@Component
class BankingTools {

    private final RestClient crm;
    private final RestClient core;

    BankingTools(RestClient.Builder builder, BankingProperties props) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build());
        requests.setReadTimeout(props.readTimeout());
        this.crm = builder.clone().requestFactory(requests).baseUrl(props.crmUrl().toString()).build();
        this.core = builder.clone().requestFactory(requests).baseUrl(props.coreUrl().toString()).build();
    }

    @Tool(description = "Customer profile from the CRM: KYC risk rating, segment, occupation, country of residence, "
            + "travel notices the customer gave the bank, and free-text notes written by bank staff.")
    CustomerProfile getCustomerProfile(@ToolParam(description = "Bank (tenant) id") String tenantId,
                                       @ToolParam(description = "Customer id") String customerId) {
        return crm.get().uri("/{tenant}/customers/{customer}", tenantId, customerId).retrieve().body(CustomerProfile.class);
    }

    @Tool(description = "Account status and its security-relevant events of the last 30 days from core banking: "
            + "changes to contact details, logins from new devices, transfer limit changes.")
    AccountDetails getAccountDetails(@ToolParam(description = "Bank (tenant) id") String tenantId,
                                     @ToolParam(description = "Account id") String accountId) {
        return core.get().uri("/{tenant}/accounts/{account}", tenantId, accountId).retrieve().body(AccountDetails.class);
    }

    record CustomerProfile(String customerId, String segment, LocalDate onboardedOn, String kycRiskRating,
                           String occupation, String residenceCountry, List<TravelNotice> travelNotices,
                           List<Note> notes) {
    }

    record TravelNotice(String country, LocalDate from, LocalDate to) {
    }

    record Note(Instant at, String author, String text) {
    }

    record AccountDetails(String accountId, String status, String product, LocalDate openedOn,
                          List<SecurityEvent> recentSecurityEvents) {
    }

    record SecurityEvent(String type, Instant at, String detail) {
    }
}
