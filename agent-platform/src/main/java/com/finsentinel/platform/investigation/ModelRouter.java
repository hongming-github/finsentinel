package com.finsentinel.platform.investigation;

import com.finsentinel.common.Alert;
import com.finsentinel.common.Channel;
import com.finsentinel.platform.llm.AgentRole;
import com.finsentinel.platform.llm.LlmProperties;
import com.finsentinel.platform.llm.ModelRef;
import com.finsentinel.platform.transaction.TransactionRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks the model for each agent on each case, deterministically: the configured model of the role, or the
 * escalation model when the case is escalated (a transfer, or an alert severity the configuration names) and the role
 * is one that escalates. Phase 3 measured gpt-5.5 9/9 against gpt-5.4-mini 8/9, the misses on transfer scams.
 */
@Component
public class ModelRouter {

    private final LlmProperties props;
    private final TransactionRepository transactions;

    ModelRouter(LlmProperties props, TransactionRepository transactions) {
        this.props = props;
        this.transactions = transactions;
    }

    public ModelRef modelFor(AgentRole role, CaseContext caseContext) {
        LlmProperties.Escalation escalation = props.escalation();
        if (escalation.roles().contains(role) && route(caseContext).escalated()) {
            return ModelRef.parse(escalation.model());
        }
        return props.model(role);
    }

    /** Why a case is, or is not, escalated. */
    public Route route(CaseContext caseContext) {
        LlmProperties.Escalation escalation = props.escalation();
        List<String> reasons = new ArrayList<>();
        caseContext.alerts().stream().map(Alert::severity).filter(escalation.onSeverity()::contains).distinct()
                .forEach(s -> reasons.add(s + " alert"));
        if (escalation.onTransfer() && transactions.findByIds(caseContext.tenantId(), caseContext.accountId(),
                caseContext.txnIds()).stream().anyMatch(t -> t.channel() == Channel.TRANSFER)) {
            reasons.add("transfer");
        }
        return new Route(!reasons.isEmpty(), reasons);
    }

    public record Route(boolean escalated, List<String> reasons) {
    }
}
