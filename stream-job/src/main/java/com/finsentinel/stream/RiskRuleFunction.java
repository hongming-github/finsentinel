package com.finsentinel.stream;

import com.finsentinel.common.AccountBaseline;
import com.finsentinel.common.Alert;
import com.finsentinel.common.Transaction;
import com.finsentinel.stream.rules.AccountRiskState;
import com.finsentinel.stream.rules.RuleConfig;
import com.finsentinel.stream.rules.RuleEngine;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.functions.co.KeyedCoProcessFunction;
import org.apache.flink.util.Collector;

import java.time.Duration;
import java.time.Instant;

/**
 * Keyed by tenant|account, with two inputs on the same key: transactions, and the account's batch baselines (ADR
 * 0019). Holds one {@link AccountRiskState} per account and delegates to {@link RuleEngine}. Keyed rather than
 * broadcast: each task holds only its own accounts' baselines, so state grows with the accounts, not with the
 * parallelism.
 */
class RiskRuleFunction extends KeyedCoProcessFunction<String, Transaction, AccountBaseline, Alert> {

    private final RuleEngine engine;
    /** Accounts idle longer than this lose their state; bounds state size. A baseline update counts as activity. */
    private final Duration stateTtl;
    private transient ValueState<AccountRiskState> state;

    RiskRuleFunction(RuleConfig config, Duration stateTtl) {
        this.engine = new RuleEngine(config);
        this.stateTtl = stateTtl;
    }

    @Override
    public void open(OpenContext openContext) {
        ValueStateDescriptor<AccountRiskState> descriptor =
                new ValueStateDescriptor<>("account-risk", AccountRiskState.class);
        descriptor.enableTimeToLive(StateTtlConfig.newBuilder(stateTtl).build());
        state = getRuntimeContext().getState(descriptor);
    }

    @Override
    public void processElement1(Transaction txn, Context ctx, Collector<Alert> out) throws Exception {
        AccountRiskState current = current();
        for (Alert alert : engine.evaluate(current, txn, Instant.now())) {
            out.collect(alert);
        }
        state.update(current);
    }

    @Override
    public void processElement2(AccountBaseline baseline, Context ctx, Collector<Alert> out) throws Exception {
        AccountRiskState current = current();
        engine.applyBaseline(current, baseline);
        state.update(current);
    }

    private AccountRiskState current() throws Exception {
        AccountRiskState current = state.value();
        return current == null ? new AccountRiskState() : current;
    }
}
