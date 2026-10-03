package com.finsentinel.mcp.compliance;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.List;

/**
 * Screening tools. Read-only; ids go into the vendor URL as encoded path variables. Like every MCP server here it
 * serves any caller, so restricting an agent to its own case is the caller's job.
 */
@Component
class ComplianceTools {

    private final RestClient screening;

    ComplianceTools(RestClient.Builder builder, ComplianceProperties props) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build());
        requests.setReadTimeout(props.readTimeout());
        this.screening = builder.clone().requestFactory(requests).baseUrl(props.screeningUrl().toString()).build();
    }

    @Tool(description = "Screen a payment counterparty (payee account or merchant id) against watchlists, including "
            + "the inter-bank list of reported money mule accounts. An empty hit list means not listed.")
    ScreeningResult screenCounterparty(@ToolParam(description = "Bank (tenant) id asking") String tenantId,
                                       @ToolParam(description = "Counterparty id as it appears on the transaction") String counterpartyId) {
        return screening.get().uri("/{tenant}/counterparties/{counterparty}", tenantId, counterpartyId)
                .retrieve().body(ScreeningResult.class);
    }

    record ScreeningResult(String counterpartyId, List<Hit> hits) {
    }

    record Hit(String list, String reason, LocalDate listedOn) {
    }
}
