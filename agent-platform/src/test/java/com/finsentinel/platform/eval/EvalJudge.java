package com.finsentinel.platform.eval;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.finsentinel.platform.investigation.InvestigationResult;
import com.finsentinel.platform.investigation.ToolEvidence;
import com.finsentinel.platform.llm.LlmGateway;
import com.finsentinel.platform.llm.LlmUsage;
import com.finsentinel.platform.llm.ModelRef;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * LLM-as-judge (ADR 0017): scores a report against the case, the expert's view and the evidence the investigator saw,
 * on the parts a rule cannot check (is every finding supported, does the verdict follow, are the actions sensible).
 * Every call goes through {@link LlmGateway}, so it is priced and counted against the run's budget. Not calibrated
 * against human labels yet: a signal next to the deterministic checks, gated only when a threshold is set.
 */
final class EvalJudge {

    record Verdict(
            @JsonPropertyDescription("1-5, or 0 when no evidence was given: every finding is backed by the evidence")
            int evidenceSupport,
            @JsonPropertyDescription("1-5: the disposition and risk score follow from the evidence")
            int conclusion,
            @JsonPropertyDescription("1-5: the recommended actions fit the case and are concrete")
            int actions,
            @JsonPropertyDescription("Statements of the report the evidence does not support, quoted; empty if none")
            List<String> unsupportedClaims,
            @JsonPropertyDescription("Two sentences explaining the scores")
            String rationale) {

        Verdict {
            unsupportedClaims = unsupportedClaims == null ? List.of() : List.copyOf(unsupportedClaims);
        }

        /** Mean of the assessed scores (0 = not assessed is left out). */
        double mean() {
            int[] scores = {evidenceSupport, conclusion, actions};
            return java.util.Arrays.stream(scores).filter(s -> s > 0).average().orElse(0);
        }
    }

    record Judged(Verdict verdict, LlmUsage usage) {
    }

    private final LlmGateway gateway;
    private final ModelRef model;
    private final JsonMapper json;
    private final String system;

    EvalJudge(LlmGateway gateway, ModelRef model, JsonMapper json) {
        this.gateway = gateway;
        this.model = model;
        this.json = json;
        try {
            this.system = new ClassPathResource("eval/judge-system.md").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Judged judge(GoldenCase c, InvestigationResult result) {
        LlmGateway.Answer<Verdict> answer = gateway.ask(model, system, message(c, result, json), List.of(),
                Verdict.class);
        return new Judged(answer.value(), answer.usage());
    }

    /** What the judge reads. The evidence is the pseudonymised text the investigator was given, nothing more. */
    static String message(GoldenCase c, InvestigationResult result, JsonMapper json) {
        StringBuilder m = new StringBuilder()
                .append("Case: ").append(c.description())
                .append("\n\nWhat an experienced analyst would conclude: ").append(c.expertView())
                .append("\n\nReport:\n").append(json.writerWithDefaultPrettyPrinter().writeValueAsString(result.report()));
        if (result.evidence().isEmpty()) {
            m.append("\n\nEvidence: not available for this report (written from specialists' findings). Tools called: ")
                    .append(result.toolCalls().stream().map(t -> t.tool()).distinct().toList());
        } else {
            m.append("\n\nEvidence the investigator's tools returned:");
            for (ToolEvidence e : result.evidence()) {
                m.append("\n- ").append(e.tool()).append("(").append(e.input()).append("): ").append(e.output());
            }
        }
        return m.toString();
    }
}
