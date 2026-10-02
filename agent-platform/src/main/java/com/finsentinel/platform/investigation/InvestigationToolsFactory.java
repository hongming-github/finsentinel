package com.finsentinel.platform.investigation;

import com.finsentinel.platform.alert.AlertRepository;
import com.finsentinel.platform.alert.StoredAlert;
import com.finsentinel.platform.knowledge.KnowledgeSearch;
import com.finsentinel.platform.transaction.TransactionRepository;
import org.springframework.stereotype.Component;

/** Builds a fresh, case-scoped tool set. Callers only say which case; the dependencies stay in here. */
@Component
class InvestigationToolsFactory {

    private final TransactionRepository transactions;
    private final AlertRepository alerts;
    private final KnowledgeSearch knowledge;
    private final InvestigationProperties props;

    InvestigationToolsFactory(TransactionRepository transactions, AlertRepository alerts, KnowledgeSearch knowledge,
                              InvestigationProperties props) {
        this.transactions = transactions;
        this.alerts = alerts;
        this.knowledge = knowledge;
        this.props = props;
    }

    InvestigationTools forCase(StoredAlert alert) {
        return new InvestigationTools(alert, transactions, alerts, knowledge, props.tools());
    }
}
