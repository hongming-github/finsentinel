package com.finsentinel.platform.llm;

import com.finsentinel.common.Severity;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Bound from {@code finsentinel.llm.*}: which model does which job, when a case is escalated to a stronger one, and
 * list prices. Invalid values fail at startup, including a configured model without a price.
 *
 * @param models     the model of each role on an ordinary case
 * @param escalation the stronger model and the roles that use it on escalated cases
 * @param prices     USD per million tokens, keyed by model name; a dated snapshot ("gpt-5.4-mini-2026-03-17") is
 *                   priced by the longest configured name it starts with
 */
@ConfigurationProperties("finsentinel.llm")
public record LlmProperties(Models models, Escalation escalation, Map<String, Price> prices) {

    public LlmProperties {
        require(models != null && escalation != null && prices != null, "models, escalation and prices are required");
        for (String model : models.byRole().values()) {
            require(prices.containsKey(model), "no price for model " + model);
        }
        require(prices.containsKey(escalation.model()), "no price for escalation model " + escalation.model());
    }

    public record Models(String investigator, String specialist, String writer, String reviewer) {

        public Models {
            require(investigator != null && specialist != null && writer != null && reviewer != null,
                    "a model for every role is required");
        }

        Map<AgentRole, String> byRole() {
            Map<AgentRole, String> out = new EnumMap<>(AgentRole.class);
            out.put(AgentRole.INVESTIGATOR, investigator);
            out.put(AgentRole.SPECIALIST, specialist);
            out.put(AgentRole.WRITER, writer);
            out.put(AgentRole.REVIEWER, reviewer);
            return out;
        }
    }

    /**
     * @param roles      roles that switch to {@code model} on an escalated case; empty turns escalation off
     * @param onTransfer escalate cases that involve a transfer
     * @param onSeverity escalate cases with an alert of one of these severities
     */
    public record Escalation(String model, Set<AgentRole> roles, boolean onTransfer, Set<Severity> onSeverity) {

        public Escalation {
            require(model != null, "escalation.model is required");
            roles = roles == null ? Set.of() : Set.copyOf(roles);
            onSeverity = onSeverity == null ? Set.of() : Set.copyOf(onSeverity);
        }
    }

    public record Price(BigDecimal input, BigDecimal cachedInput, BigDecimal output) {

        public Price {
            require(input != null && cachedInput != null && output != null
                    && input.signum() >= 0 && cachedInput.signum() >= 0 && output.signum() >= 0,
                    "prices must be zero or positive");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("finsentinel.llm." + message);
        }
    }
}
