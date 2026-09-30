package com.finsentinel.stream;

import com.finsentinel.common.Alert;
import com.finsentinel.common.Transaction;
import com.finsentinel.stream.rules.AccountRiskState;
import com.finsentinel.stream.rules.RuleConfig;
import com.finsentinel.stream.rules.RuleEngine;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.time.Duration;
import java.time.Instant;

/** Keyed by tenant|account. Holds one {@link AccountRiskState} per account and delegates to {@link RuleEngine}. */
class RiskRuleFunction extends KeyedProcessFunction<String, Transaction, Alert> {

    /** Accounts idle longer than this lose their baseline; bounds state size. */
    private static final Duration STATE_TTL = Duration.ofDays(30);

    private final RuleEngine engine;
    private transient ValueState<AccountRiskState> state;

    RiskRuleFunction(RuleConfig config) {
        this.engine = new RuleEngine(config);
    }

    @Override
    public void open(OpenContext openContext) {
        ValueStateDescriptor<AccountRiskState> descriptor =
                new ValueStateDescriptor<>("account-risk", AccountRiskState.class);
        descriptor.enableTimeToLive(StateTtlConfig.newBuilder(STATE_TTL).build());
        state = getRuntimeContext().getState(descriptor);
    }

    @Override
    public void processElement(Transaction txn, Context ctx, Collector<Alert> out) throws Exception {
        AccountRiskState current = state.value();
        if (current == null) {
            current = new AccountRiskState();
        }
        for (Alert alert : engine.evaluate(current, txn, Instant.now())) {
            out.collect(alert);
        }
        state.update(current);
    }
}
