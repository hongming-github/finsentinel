package com.finsentinel.platform.cases;

import com.finsentinel.common.Severity;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.StubCaseInvestigator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static com.finsentinel.platform.TestData.uniqueTenant;
import static com.finsentinel.platform.cases.CaseTestSupport.alertOn;
import static com.finsentinel.platform.cases.CaseTestSupport.eventTypes;
import static com.finsentinel.platform.cases.CaseTestSupport.outboxTypes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@PlatformTest
class CaseDecisionApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    CaseIntake intake;
    @Autowired
    CaseWorker worker;
    @Autowired
    CaseRepository cases;
    @Autowired
    StubCaseInvestigator investigator;
    @Autowired
    JdbcClient jdbc;

    private final String tenant = uniqueTenant();
    private String caseId;

    @BeforeEach
    void caseAwaitingDecision() {
        CaseTestSupport.parkOtherCases(jdbc);
        caseId = intake.accept(alertOn(tenant, "acc-" + UUID.randomUUID(), Severity.HIGH)).orElseThrow();
        worker.runOnce();
        assertThat(cases.find(tenant, caseId).orElseThrow().stage()).isEqualTo(CaseStage.AWAITING_DECISION);
    }

    @AfterEach
    void resetStub() {
        investigator.reset();
    }

    @Test
    void detailCarriesTheVersionAsETagWithAlertsAnalysesAndEvents() throws Exception {
        mvc.perform(get("/api/tenants/{t}/cases/{id}", tenant, caseId))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"" + version() + "\""))
                .andExpect(jsonPath("$.alerts.length()").value(1))
                .andExpect(jsonPath("$.analyses[0].run").value(1))
                .andExpect(jsonPath("$.events[*].type").value(org.hamcrest.Matchers.contains("OPENED", "ANALYSED")));
    }

    @Test
    void decisionWithoutIfMatchIsRefused() throws Exception {
        mvc.perform(decision("FALSE_POSITIVE", null).header("X-Analyst-Id", "ana"))
                .andExpect(status().isPreconditionRequired());
    }

    @Test
    void decisionOnAStaleVersionIsRefusedWithTheCurrentCase() throws Exception {
        int seen = version();
        intake.accept(alertOn(tenant, cases.find(tenant, caseId).orElseThrow().accountId(), Severity.MEDIUM));

        mvc.perform(decision("FALSE_POSITIVE", null).header("If-Match", "\"" + seen + "\"").header("X-Analyst-Id", "ana"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.stage").value("ANALYSIS"))
                .andExpect(header().string("ETag", "\"" + version() + "\""));
    }

    @Test
    void anotherTenantCannotSeeOrDecideTheCase() throws Exception {
        String other = uniqueTenant();
        mvc.perform(get("/api/tenants/{t}/cases/{id}", other, caseId)).andExpect(status().isNotFound());
        mvc.perform(post("/api/tenants/{t}/cases/{id}/decision", other, caseId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"FALSE_POSITIVE\"}")
                        .header("If-Match", "\"" + version() + "\"").header("X-Analyst-Id", "ana"))
                .andExpect(status().isNotFound());
    }

    @Test
    void closingDecisionClosesTheCaseAuditsItAndTellsDownstream() throws Exception {
        mvc.perform(decision("CONFIRMED_FRAUD", "card blocked").header("If-Match", "\"" + version() + "\"")
                        .header("X-Analyst-Id", "ana-7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("CLOSED"))
                .andExpect(jsonPath("$.decision").value("CONFIRMED_FRAUD"))
                .andExpect(jsonPath("$.decidedBy").value("ana-7"));

        assertThat(eventTypes(cases, tenant, caseId)).endsWith("DECIDED");
        assertThat(cases.events(tenant, caseId).getLast().actor()).isEqualTo("ana-7");
        assertThat(outboxTypes(jdbc, caseId)).endsWith("case.decided");
    }

    @Test
    void reanalysisNeedsANoteAndTheNoteReachesTheAgent() throws Exception {
        mvc.perform(decision("REQUEST_REANALYSIS", " ").header("If-Match", "\"" + version() + "\"").header("X-Analyst-Id", "ana"))
                .andExpect(status().isBadRequest());

        String note = "Customer confirms travel to London; look again at the card-not-present burst.";
        mvc.perform(decision("REQUEST_REANALYSIS", note).header("If-Match", "\"" + version() + "\"").header("X-Analyst-Id", "ana"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("ANALYSIS"))
                .andExpect(jsonPath("$.analysisRun").value(2));
        worker.runOnce();

        assertThat(investigator.lastContext().analystNote()).isEqualTo(note);
        assertThat(investigator.lastContext().run()).isEqualTo(2);
        assertThat(cases.find(tenant, caseId).orElseThrow().stage()).isEqualTo(CaseStage.AWAITING_DECISION);
    }

    private int version() {
        return cases.find(tenant, caseId).orElseThrow().version();
    }

    private MockHttpServletRequestBuilder decision(String decision, String note) {
        String body = note == null ? "{\"decision\":\"%s\"}".formatted(decision)
                : "{\"decision\":\"%s\",\"note\":\"%s\"}".formatted(decision, note);
        return post("/api/tenants/{t}/cases/{id}/decision", tenant, caseId)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
