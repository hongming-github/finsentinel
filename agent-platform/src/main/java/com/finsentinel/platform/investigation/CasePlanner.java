package com.finsentinel.platform.investigation;

import com.finsentinel.common.Channel;
import com.finsentinel.platform.security.Tenancy;
import com.finsentinel.platform.transaction.TransactionRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** The Plan step: deterministic routing from the case's data. */
@Component
public class CasePlanner {

    private final TransactionRepository transactions;

    CasePlanner(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    /** Runs as a graph node, on a pool thread without a scope of its own: the case supplies it. */
    public AnalysisPlan plan(CaseContext caseContext) {
        boolean transfer = Tenancy.call(caseContext.tenantId(), () -> transactions.findByIds(caseContext.tenantId(),
                        caseContext.accountId(), caseContext.txnIds()))
                .stream().anyMatch(t -> t.channel() == Channel.TRANSFER);
        Set<Specialist> specialists = EnumSet.of(Specialist.TRANSACTIONS);
        List<String> reasons = new ArrayList<>(List.of("transactions specialist runs on every case"));
        if (caseContext.connectedSystems().isEmpty()) {
            reasons.add("the bank has connected no outside systems: customer specialist skipped");
        } else {
            specialists.add(Specialist.CUSTOMER);
            reasons.add("customer specialist for the bank's connected systems " + caseContext.connectedSystems());
        }
        if (transfer) {
            specialists.add(Specialist.REGULATIONS);
            reasons.add("a transfer is involved: regulations specialist for loss responsibility and reporting duties");
        } else {
            reasons.add("no transfer: regulations specialist skipped");
        }
        return new AnalysisPlan(specialists, transfer, reasons);
    }
}
