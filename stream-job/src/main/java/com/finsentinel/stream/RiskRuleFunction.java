package com.finsentinel.stream;

import com.finsentinel.common.AccountBaseline;
import com.finsentinel.common.Alert;
import com.finsentinel.telemetry.Telemetry;
import com.finsentinel.stream.rules.AccountRiskState;
import com.finsentinel.stream.rules.RuleConfig;
import com.finsentinel.stream.rules.RuleEngine;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.functions.co.KeyedCoProcessFunction;
import org.apache.flink.util.Collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * Keyed by tenant|account, with two inputs on the same key: transactions, and the account's batch baselines (ADR
 * 0019). Holds one {@link AccountRiskState} per account and delegates to {@link RuleEngine}. Keyed rather than
 * broadcast: each task holds only its own accounts' baselines, so state grows with the accounts, not with the
 * parallelism.
 */
class RiskRuleFunction extends KeyedCoProcessFunction<String, TracedTransaction, AccountBaseline, TracedAlert> {

    private static final Logger log = LoggerFactory.getLogger(RiskRuleFunction.class);

    private final RuleEngine engine;
    /** Accounts idle longer than this lose their state; bounds state size. A baseline update counts as activity. */
    private final Duration stateTtl;
    /** OTLP/HTTP receiver for alert spans (ADR 0020); empty = no spans, the transaction's context is forwarded. */
    private final String otlpEndpoint;
    private transient ValueState<AccountRiskState> state;
    private transient AlertTracing tracing;

    RiskRuleFunction(RuleConfig config, Duration stateTtl, String otlpEndpoint) {
        this.engine = new RuleEngine(config);
        this.stateTtl = stateTtl;
        this.otlpEndpoint = otlpEndpoint;
    }

    @Override
    public void open(OpenContext openContext) {
        ValueStateDescriptor<AccountRiskState> descriptor =
                new ValueStateDescriptor<>("account-risk", AccountRiskState.class);
        descriptor.enableTimeToLive(StateTtlConfig.newBuilder(stateTtl).build());
        state = getRuntimeContext().getState(descriptor);
        tracing = new AlertTracing(telemetry(otlpEndpoint));
    }

    @Override
    public void processElement1(TracedTransaction traced, Context ctx, Collector<TracedAlert> out) throws Exception {
        AccountRiskState current = current();
        for (Alert alert : engine.evaluate(current, traced.txn(), Instant.now())) {
            out.collect(new TracedAlert(alert, tracing.detected(alert, traced.traceparent())));
        }
        state.update(current);
    }

    @Override
    public void processElement2(AccountBaseline baseline, Context ctx, Collector<TracedAlert> out) throws Exception {
        AccountRiskState current = current();
        engine.applyBaseline(current, baseline);
        state.update(current);
    }

    /**
     * Tracing that cannot start (a broken exporter set-up) is logged and switched off: it must never stop detection,
     * which a failure here would do by failing the task on every restart.
     */
    private static Telemetry telemetry(String otlpEndpoint) {
        try {
            return Telemetry.shared("stream-job", otlpEndpoint);
        } catch (RuntimeException | LinkageError e) {
            log.error("Alert tracing could not start and is off: {}", e.toString());
            return Telemetry.start("stream-job", "");
        }
    }

    private AccountRiskState current() throws Exception {
        AccountRiskState current = state.value();
        return current == null ? new AccountRiskState() : current;
    }
}
