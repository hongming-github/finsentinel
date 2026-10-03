package com.finsentinel.platform.investigation;

/** Runs one specialist on a case. The seam between graph and LLM for the Execute step. */
public interface SpecialistAgents {

    SpecialistReport run(Specialist specialist, CaseContext caseContext);
}
