package com.finsentinel.platform.usage;

import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Severity;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.StubCaseInvestigator;
import com.finsentinel.platform.TestTokens;
import com.finsentinel.platform.cases.CaseIntake;
import com.finsentinel.platform.cases.CaseWorker;
import com.finsentinel.platform.investigation.InvestigationResult;
import com.finsentinel.platform.investigation.ToolCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.finsentinel.platform.TestData.alert;
import static com.finsentinel.platform.TestData.uniqueTenant;
import static com.finsentinel.platform.TestTokens.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@PlatformTest
class UsageReportTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    CaseIntake intake;
    @Autowired
    CaseWorker worker;
    @Autowired
    StubCaseInvestigator investigator;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void reset() {
        investigator.reset();
    }

    @Test
    void costPerCaseInTotalAndByModel() throws Exception {
        String tenant = uniqueTenant();
        AtomicInteger n = new AtomicInteger();
        // First case on the small model only, second also escalated to the large one.
        // Searches: a miss and a hit in the first case; the second searched with the cache off (not counted).
        investigator.respondWith(c -> n.incrementAndGet() == 1
                ? costed(StubCaseInvestigator.defaultResult(c), Map.of("gpt-5.4-mini", new BigDecimal("0.002")),
                List.of(search(false, null), search(true, new BigDecimal("0.0008")),
                        new ToolCall("getAlertTransactions", "", 1, 1)))
                : costed(StubCaseInvestigator.defaultResult(c),
                Map.of("gpt-5.4-mini", new BigDecimal("0.001"), "gpt-5.5", new BigDecimal("0.020")),
                List.of(search(null, null))));
        analyse(tenant);
        analyse(tenant);

        mvc.perform(get("/api/tenants/{t}/llm-usage", tenant).with(bearer(TestTokens.supervisor(tenant))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.costPerCase.analyses").value(2))
                .andExpect(jsonPath("$.costPerCase.total").value(0.023))
                .andExpect(jsonPath("$.costPerCase.max").value(0.021))
                .andExpect(jsonPath("$.costByModel['gpt-5.5']").value(0.02))
                .andExpect(jsonPath("$.costByModel['gpt-5.4-mini']").value(0.003))
                .andExpect(jsonPath("$.byMode[0].mode").value("SINGLE"))
                .andExpect(jsonPath("$.regulationSearchCache.searches").value(2))
                .andExpect(jsonPath("$.regulationSearchCache.hits").value(1))
                .andExpect(jsonPath("$.regulationSearchCache.hitRate").value(0.5))
                .andExpect(jsonPath("$.regulationSearchCache.savedUsd").value(0.0008));
        String empty = uniqueTenant();
        mvc.perform(get("/api/tenants/{t}/llm-usage", empty).with(bearer(TestTokens.supervisor(empty))))
                .andExpect(jsonPath("$.costPerCase.analyses").value(0))
                .andExpect(jsonPath("$.regulationSearchCache.searches").value(0))
                .andExpect(jsonPath("$.regulationSearchCache.hitRate").doesNotExist());
    }

    private void analyse(String tenant) {
        jdbc.sql("update fraud_case set stage = 'CLOSED', claimed_at = null where stage = 'ANALYSIS'").update();
        intake.accept(alert(tenant, "acc-" + UUID.randomUUID(), AlertRule.GEO_JUMP, Severity.MEDIUM, List.of("t-1"),
                Instant.now()));
        worker.runOnce();
    }

    private static ToolCall search(Boolean cacheHit, BigDecimal savedUsd) {
        return new ToolCall("searchRegulations", "cooling off period", 3, 5, cacheHit, savedUsd);
    }

    private static InvestigationResult costed(InvestigationResult r, Map<String, BigDecimal> byModel,
                                              List<ToolCall> toolCalls) {
        BigDecimal total = byModel.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return new InvestigationResult(r.report(), toolCalls, r.repairedIssues(), r.model(), r.inputTokens(),
                r.outputTokens(), r.cachedInputTokens(), r.llmCalls(), r.latencyMs(), List.of(), total, byModel);
    }
}
