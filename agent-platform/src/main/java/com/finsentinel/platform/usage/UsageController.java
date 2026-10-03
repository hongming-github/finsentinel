package com.finsentinel.platform.usage;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a bank's case analyses cost: per case and in total, split by model and by analysis mode. Answers "what does
 * an alert cost us" and shows what routing to a stronger model is buying.
 */
@RestController
@RequestMapping("/api/tenants/{tenantId}/llm-usage")
class UsageController {

    private final JdbcClient jdbc;
    private final UsageProperties props;

    UsageController(JdbcClient jdbc, UsageProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @GetMapping
    UsageReport report(@PathVariable String tenantId, @RequestParam(defaultValue = "7") int days) {
        int d = Math.clamp(days, 1, props.maxDays());
        CostStats total = jdbc.sql("""
                        select count(*) as analyses, coalesce(sum(a.cost_usd), 0) as total,
                               avg(a.cost_usd) as average,
                               percentile_cont(0.95) within group (order by a.cost_usd) as p95,
                               max(a.cost_usd) as max
                        from case_analysis a join fraud_case c using (case_id)
                        where c.tenant_id = :tenantId and a.cost_usd is not null
                          and a.created_at > now() - make_interval(days => :days)
                        """)
                .param("tenantId", tenantId)
                .param("days", d)
                .query((rs, n) -> new CostStats(rs.getInt("analyses"), rs.getBigDecimal("total"),
                        rs.getBigDecimal("average"), rs.getBigDecimal("p95"), rs.getBigDecimal("max")))
                .single();
        Map<String, BigDecimal> byModel = new TreeMap<>();
        jdbc.sql("""
                        select m.key as model, sum(cast(m.value as numeric)) as cost
                        from case_analysis a join fraud_case c using (case_id),
                             jsonb_each_text(a.cost_by_model) m
                        where c.tenant_id = :tenantId and a.created_at > now() - make_interval(days => :days)
                        group by m.key
                        """)
                .param("tenantId", tenantId)
                .param("days", d)
                .query((rs, n) -> byModel.put(rs.getString("model"), rs.getBigDecimal("cost")))
                .list();
        List<ModeStats> byMode = jdbc.sql("""
                        select a.mode, count(*) as analyses, avg(a.cost_usd) as average_cost,
                               avg(a.latency_ms) as average_latency_ms, avg(a.llm_calls) as average_calls
                        from case_analysis a join fraud_case c using (case_id)
                        where c.tenant_id = :tenantId and a.cost_usd is not null
                          and a.created_at > now() - make_interval(days => :days)
                        group by a.mode order by a.mode
                        """)
                .param("tenantId", tenantId)
                .param("days", d)
                .query((rs, n) -> new ModeStats(rs.getString("mode"), rs.getInt("analyses"),
                        rs.getBigDecimal("average_cost"), rs.getLong("average_latency_ms"),
                        rs.getDouble("average_calls")))
                .list();
        return new UsageReport(d, total, byModel, byMode);
    }

    record UsageReport(int days, CostStats costPerCase, Map<String, BigDecimal> costByModel, List<ModeStats> byMode) {
    }

    /** Over analyses with a recorded cost; {@code total} is their sum. */
    record CostStats(int analyses, BigDecimal total, BigDecimal average, BigDecimal p95, BigDecimal max) {
    }

    record ModeStats(String mode, int analyses, BigDecimal averageCost, long averageLatencyMs, double averageCalls) {
    }
}
