package com.finsentinel.platform.analysis;

import com.finsentinel.platform.investigation.CaseInvestigator;
import com.finsentinel.platform.investigation.CasePlanner;
import com.finsentinel.platform.investigation.CaseReviewer;
import com.finsentinel.platform.investigation.ReportWriter;
import com.finsentinel.platform.investigation.SpecialistAgents;
import org.springframework.context.ApplicationContext;

import java.util.Collection;

/**
 * The analysis graph with the real agents, for tests that call models (live tests, the evaluation). The shared test
 * context makes the stubs primary; this picks the other implementation of each agent.
 */
public final class RealAgentGraph {

    private RealAgentGraph() {
    }

    public static CaseAnalysisGraph of(ApplicationContext context) {
        try {
            return new CaseAnalysisGraph(real(context, CaseInvestigator.class), real(context, SpecialistAgents.class),
                    real(context, ReportWriter.class), real(context, CaseReviewer.class),
                    context.getBean(CasePlanner.class), context.getBean(FencedPostgresCheckpointSaver.class),
                    context.getBean(CaseGraphStateSerializer.class), context.getBean(AnalysisProperties.class),
                    io.opentelemetry.api.OpenTelemetry.noop().getTracer("test"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T real(ApplicationContext context, Class<T> type) {
        Collection<T> beans = context.getBeansOfType(type).values();
        return beans.stream().filter(b -> !b.getClass().getSimpleName().startsWith("Stub")).findFirst().orElseThrow();
    }
}
