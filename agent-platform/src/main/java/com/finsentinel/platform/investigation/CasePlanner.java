package com.finsentinel.platform.investigation;

import com.finsentinel.common.Channel;
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

    public AnalysisPlan plan(CaseContext caseContext) {
        boolean transfer = transactions.findByIds(caseContext.tenantId(), caseContext.accountId(), caseContext.txnIds())
                .stream().anyMatch(t -> t.channel() == Channel.TRANSFER);
        Set<Specialist> specialists = EnumSet.of(Specialist.TRANSACTIONS, Specialist.CUSTOMER);
        List<String> reasons = new ArrayList<>(List.of(
                "transactions and customer specialists run on every case"));
        if (transfer) {
            specialists.add(Specialist.REGULATIONS);
            reasons.add("a transfer is involved: regulations specialist for loss responsibility and reporting duties");
        } else {
            reasons.add("no transfer: regulations specialist skipped");
        }
        return new AnalysisPlan(specialists, transfer, reasons);
    }
}
