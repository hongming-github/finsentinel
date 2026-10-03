package com.finsentinel.platform.investigation;

import java.util.List;
import java.util.Set;

/**
 * Which specialists a case needs, decided from the data rather than by a model: a missed fraud costs far more than
 * the calls a model-based triage would save, and the routing needs no judgement.
 *
 * @param reasons why each choice was made, for the audit trail
 */
public record AnalysisPlan(Set<Specialist> specialists, boolean involvesTransfer, List<String> reasons) {

    public AnalysisPlan {
        specialists = Set.copyOf(specialists);
        reasons = List.copyOf(reasons);
    }
}
