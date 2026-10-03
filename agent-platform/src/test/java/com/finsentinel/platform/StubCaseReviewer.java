package com.finsentinel.platform;

import com.finsentinel.platform.investigation.CaseContext;
import com.finsentinel.platform.investigation.CaseReviewer;
import com.finsentinel.platform.investigation.InvestigationResult;
import com.finsentinel.platform.investigation.LlmUsage;
import com.finsentinel.platform.investigation.Review;
import com.finsentinel.platform.investigation.SpecialistReport;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * Replaces the LLM reviewer in tests, so tests can make the last graph node fail, block or send reports back.
 * Remembers the evidence it was given. reset() restores approval.
 */
@TestComponent
@Primary
public class StubCaseReviewer implements CaseReviewer {

    private volatile BiFunction<CaseContext, InvestigationResult, Review> behaviour = StubCaseReviewer::approve;
    private volatile List<SpecialistReport> lastEvidence;
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public Review review(CaseContext caseContext, InvestigationResult investigation, List<SpecialistReport> evidence) {
        calls.incrementAndGet();
        lastEvidence = evidence;
        return behaviour.apply(caseContext, investigation);
    }

    public void respondWith(BiFunction<CaseContext, InvestigationResult, Review> behaviour) {
        this.behaviour = behaviour;
    }

    public int calls() {
        return calls.get();
    }

    public List<SpecialistReport> lastEvidence() {
        return lastEvidence;
    }

    public void reset() {
        behaviour = StubCaseReviewer::approve;
        lastEvidence = null;
        calls.set(0);
    }

    public static Review approve(CaseContext caseContext, InvestigationResult investigation) {
        return new Review(true, List.of(), LlmUsage.NONE);
    }

    public static Review sendBack(String issue) {
        return new Review(false, List.of(issue), new LlmUsage("stub-reviewer", 7, 3, null, 1, 1));
    }
}
