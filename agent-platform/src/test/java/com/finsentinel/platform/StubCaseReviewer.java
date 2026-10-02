package com.finsentinel.platform;

import com.finsentinel.platform.analysis.CaseReviewer;
import com.finsentinel.platform.analysis.Review;
import com.finsentinel.platform.investigation.CaseContext;
import com.finsentinel.platform.investigation.InvestigationResult;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/** Replaces the reviewer in tests, so tests can make the last graph node fail or block. reset() restores approval. */
@TestComponent
@Primary
public class StubCaseReviewer implements CaseReviewer {

    private volatile BiFunction<CaseContext, InvestigationResult, Review> behaviour = StubCaseReviewer::approve;
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public Review review(CaseContext caseContext, InvestigationResult investigation) {
        calls.incrementAndGet();
        return behaviour.apply(caseContext, investigation);
    }

    public void respondWith(BiFunction<CaseContext, InvestigationResult, Review> behaviour) {
        this.behaviour = behaviour;
    }

    public int calls() {
        return calls.get();
    }

    public void reset() {
        behaviour = StubCaseReviewer::approve;
        calls.set(0);
    }

    private static Review approve(CaseContext caseContext, InvestigationResult investigation) {
        return new Review(true, "stub approval");
    }
}
