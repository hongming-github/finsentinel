package com.finsentinel.platform;

import com.finsentinel.platform.investigation.CaseContext;
import com.finsentinel.platform.investigation.CaseReport;
import com.finsentinel.platform.investigation.LlmUsage;
import com.finsentinel.platform.investigation.Specialist;
import com.finsentinel.platform.investigation.SpecialistAgents;
import com.finsentinel.platform.investigation.SpecialistReport;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/** Replaces the LLM specialists in tests. Counts calls per specialist; behaviour is swappable; reset() restores it. */
@TestComponent
@Primary
public class StubSpecialistAgents implements SpecialistAgents {

    private volatile BiFunction<Specialist, CaseContext, SpecialistReport> behaviour = StubSpecialistAgents::defaultReport;
    private final Map<Specialist, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final Map<Specialist, String> threads = new ConcurrentHashMap<>();

    @Override
    public SpecialistReport run(Specialist specialist, CaseContext caseContext) {
        calls.computeIfAbsent(specialist, s -> new AtomicInteger()).incrementAndGet();
        threads.put(specialist, Thread.currentThread().getName());
        return behaviour.apply(specialist, caseContext);
    }

    public void respondWith(BiFunction<Specialist, CaseContext, SpecialistReport> behaviour) {
        this.behaviour = behaviour;
    }

    public int calls(Specialist specialist) {
        return calls.getOrDefault(specialist, new AtomicInteger()).get();
    }

    public int totalCalls() {
        return calls.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    public Map<Specialist, String> threads() {
        return Map.copyOf(threads);
    }

    public void reset() {
        behaviour = StubSpecialistAgents::defaultReport;
        calls.clear();
        threads.clear();
    }

    public static SpecialistReport defaultReport(Specialist specialist, CaseContext caseContext) {
        return new SpecialistReport(specialist, false, specialist + " stub summary",
                List.of(new CaseReport.Finding(specialist + " stub finding", caseContext.txnIds())), List.of(),
                List.of(), Set.copyOf(caseContext.txnIds()), Set.of(),
                new LlmUsage("stub-specialist", 100, 20, null, 1, 1));
    }
}
