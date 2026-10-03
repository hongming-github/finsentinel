package com.finsentinel.platform.usage;

import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Severity;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.StubCaseInvestigator;
import com.finsentinel.platform.cases.CaseIntake;
import com.finsentinel.platform.cases.CaseWorker;
import com.finsentinel.platform.investigation.InvestigationResult;
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
        investigator.respondWith(c -> costed(StubCaseInvestigator.defaultResult(c), n.incrementAndGet() == 1
                ? Map.of("gpt-5.4-mini", new BigDecimal("0.002"))
                : Map.of("gpt-5.4-mini", new BigDecimal("0.001"), "gpt-5.5", new BigDecimal("0.020"))));
        analyse(tenant);
        analyse(tenant);

        mvc.perform(get("/api/tenants/{t}/llm-usage", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.costPerCase.analyses").value(2))
                .andExpect(jsonPath("$.costPerCase.total").value(0.023))
                .andExpect(jsonPath("$.costPerCase.max").value(0.021))
                .andExpect(jsonPath("$.costByModel['gpt-5.5']").value(0.02))
                .andExpect(jsonPath("$.costByModel['gpt-5.4-mini']").value(0.003))
                .andExpect(jsonPath("$.byMode[0].mode").value("SINGLE"));
        mvc.perform(get("/api/tenants/{t}/llm-usage", uniqueTenant()))
                .andExpect(jsonPath("$.costPerCase.analyses").value(0));
    }

    private void analyse(String tenant) {
        jdbc.sql("update fraud_case set stage = 'CLOSED', claimed_at = null where stage = 'ANALYSIS'").update();
        intake.accept(alert(tenant, "acc-" + UUID.randomUUID(), AlertRule.GEO_JUMP, Severity.MEDIUM, List.of("t-1"),
                Instant.now()));
        worker.runOnce();
    }

    private static InvestigationResult costed(InvestigationResult r, Map<String, BigDecimal> byModel) {
        BigDecimal total = byModel.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return new InvestigationResult(r.report(), r.toolCalls(), r.repairedIssues(), r.model(), r.inputTokens(),
                r.outputTokens(), r.cachedInputTokens(), r.llmCalls(), r.latencyMs(), List.of(), total, byModel);
    }
}
