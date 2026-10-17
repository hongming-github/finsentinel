#!/usr/bin/env python3
"""Runs one chaos drill (phase 9C) against the local stack and records what happened. Standard library only.

The stack runs (scripts/stack.sh up), and so do the Flink job and steady traffic with fraud scenarios, the simulator's
log going to a file. The drill then:

  1. samples every few seconds: cases waiting for analysis and claimed, alerts, analyses and FAILED cases since the
     start, Kafka end offsets of every txn/risk/case topic, health of the platform, MCP servers and fake-llm, and the
     Flink job's state, restarts and completed checkpoints;
  2. waits --baseline seconds, runs --inject, waits --hold seconds, runs --heal;
  3. calls it recovered when every process that was up at the start is up again, an analysis was stored after the
     heal, and the backlog is back to the baseline's maximum; then samples --tail seconds more (--settle caps it);
  4. writes to --out: samples.csv, marks.json, summary.json, summary.md (scenarios injected vs alerts, lost or
     duplicated messages, dead letters, FAILED cases, payment-to-report latency before, during and after the fault,
     recovery times), chart.svg, the platform's warnings in the window, and Grafana screenshots (headless Chrome).

Everything in summary.json is recomputed from Postgres, Kafka offsets and the logs, so a drill can be re-reported with
"report" as long as the database still holds the window. Grafana keeps its series only while its container lives,
which is why the screenshots are taken at the end of the drill.

    scripts/perf/drill.py run NAME --out DIR --sim-log FILE --inject CMD [--heal CMD] [--hold 60] [--baseline 60]
                         [--tail 30] [--settle 600] [--interval 5]
    scripts/perf/drill.py report DIR --sim-log FILE        # summary and chart again from samples and marks
    scripts/perf/drill.py shots DIR                         # screenshots again (only while Grafana has the data)
"""
import argparse
import csv
import datetime as dt
import json
import os
import re
import statistics
import subprocess
import sys
import threading
import time
import urllib.request

PG = ["docker", "exec", "-i", "finsentinel-postgres-1", "psql", "-U", "finsentinel", "-d", "finsentinel", "-AtqX",
      "-F", "|", "-v", "ON_ERROR_STOP=1"]
KAFKA = "finsentinel-kafka-1"
TOPICS = r"(txn|risk|case)\..*"
HEALTH = {
    "platform": "http://localhost:8090/actuator/health",
    "mcp-banking": "http://localhost:8091/actuator/health",
    "mcp-compliance": "http://localhost:8092/actuator/health",
    "fake-llm": "http://localhost:8096/health",
}
FLINK = os.environ.get("FLINK_URL", "http://localhost:8082")  # 8081 for the compose cluster (profile stream)
GRAFANA = "http://localhost:3000/d/finsentinel-pipeline/finsentinel-pipeline"
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
PLATFORM_LOG = "target/stack/platform.log"
# The rule each scenario should trip. GEO_JUMP between neighbours (Malaysia-Thailand) trips nothing on purpose.
RULE_OF = {"BURST": "VELOCITY", "GEO_JUMP": "GEO_JUMP", "LARGE_AMOUNT": "LARGE_AMOUNT"}
# A scenario injected this close to the end may not have reached the platform yet: left out of the comparison.
DETECTION_GRACE_S = 90


def now():
    return dt.datetime.now(dt.timezone.utc)


def iso(t):
    return t.isoformat(timespec="milliseconds")


def parse_iso(s):
    return dt.datetime.fromisoformat(s)


def sql(query, **params):
    """Runs a query through psql in the Postgres container; values are psql variables (:'name'), never spliced."""
    args = list(PG)
    for name, value in params.items():
        args += ["-v", f"{name}={value}"]
    out = subprocess.run(args, input=query, capture_output=True, text=True, check=True).stdout
    return [line.split("|") for line in out.splitlines() if line]


def http_status(url, timeout=2):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            return response.status
    except Exception:  # noqa: BLE001 - any failure means "not answering"
        return 0


def get_json(url, timeout=2):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return json.load(response)


def kafka_offsets():
    """End offsets summed per topic; empty when Kafka does not answer (that is a sample, not an error)."""
    try:
        out = subprocess.run(["docker", "exec", KAFKA, "/opt/kafka/bin/kafka-get-offsets.sh", "--bootstrap-server",
                              "localhost:9092", "--topic", TOPICS], capture_output=True, text=True, timeout=20).stdout
    except subprocess.TimeoutExpired:
        return {}
    totals = {}
    for line in out.split():
        topic, _, offset = line.rpartition(":")
        topic = topic.rpartition(":")[0]
        if topic and offset.isdigit():
            totals[topic] = totals.get(topic, 0) + int(offset)
    return totals


def offsets_at(topic, t):
    """Per partition, the offset of the first record whose timestamp is at or after t (Kafka's time index)."""
    out = subprocess.run(["docker", "exec", KAFKA, "/opt/kafka/bin/kafka-get-offsets.sh", "--bootstrap-server",
                          "localhost:9092", "--topic", topic, "--time", str(int(t.timestamp() * 1000))],
                         capture_output=True, text=True, check=True).stdout
    offsets = {}
    for line in out.split():
        name, partition, offset = line.rsplit(":", 2)
        if name == topic and offset.lstrip("-").isdigit():
            offsets[int(partition)] = int(offset)
    return offsets


def records_between(topic, a, b):
    """Records with a timestamp in [a, b), from the time index; a partition with nothing after a time reads -1."""
    start, end = offsets_at(topic, a), offsets_at(topic, b)
    latest = offsets_at(topic, now() + dt.timedelta(days=1))
    total = 0
    for p in start:
        s = start[p] if start[p] >= 0 else latest.get(p, 0)
        e = end.get(p, -1) if end.get(p, -1) >= 0 else latest.get(p, 0)
        total += e - s
    return total


def flink_state():
    try:
        jobs = get_json(f"{FLINK}/jobs/overview")["jobs"]
    except Exception:  # noqa: BLE001
        return {"flink": "down"}
    if not jobs:
        return {"flink": "no-job"}
    job = max(jobs, key=lambda j: j["start-time"])
    state = {"flink": job["state"], "flink_job": job["jid"]}
    try:
        metrics = get_json(f"{FLINK}/jobs/{job['jid']}/metrics?get=numRestarts")
        state["flink_restarts"] = int(float(metrics[0]["value"])) if metrics else None
        counts = get_json(f"{FLINK}/jobs/{job['jid']}/checkpoints")["counts"]
        state["flink_checkpoints"] = counts["completed"]
    except Exception:  # noqa: BLE001
        pass
    return state


def sample(start):
    row = {"t": iso(now())}
    try:
        (backlog, claimed, alerts, analyses, failed), = sql("""
            select (select count(*) from fraud_case where stage = 'ANALYSIS'),
                   (select count(*) from fraud_case where stage = 'ANALYSIS' and claimed_at is not null),
                   (select count(*) from alert where received_at >= :'start'),
                   (select count(*) from case_analysis where created_at >= :'start'),
                   (select count(*) from case_event where at >= :'start' and to_stage = 'FAILED')
            """, start=iso(start))
        row.update(backlog=int(backlog), claimed=int(claimed), alerts=int(alerts), analyses=int(analyses),
                   failed=int(failed), postgres="up")
    except subprocess.CalledProcessError:
        row["postgres"] = "down"
    for name, url in HEALTH.items():
        row[name] = "up" if http_status(url) == 200 else "down"
    row.update(flink_state())
    for topic, offset in kafka_offsets().items():
        row[f"offset:{topic}"] = offset
    return row


class Sampler(threading.Thread):
    def __init__(self, start, interval):
        super().__init__(daemon=True)
        self.start_time, self.interval, self.rows, self.stopped = start, interval, [], threading.Event()

    def run(self):
        while not self.stopped.is_set():
            began = time.monotonic()
            self.rows.append(sample(self.start_time))
            self.stopped.wait(max(0.0, self.interval - (time.monotonic() - began)))

    def latest(self):
        return self.rows[-1] if self.rows else {}


def shell(command, log):
    print(f"$ {command}", flush=True)
    result = subprocess.run(["bash", "-c", command], capture_output=True, text=True)
    log.append({"t": iso(now()), "command": command, "exit": result.returncode,
                "output": (result.stdout + result.stderr)[-2000:]})
    if result.returncode != 0:
        print(f"  exit {result.returncode}: {(result.stdout + result.stderr).strip()[-500:]}", flush=True)


def up_at_start(rows):
    first = rows[0]
    names = [n for n in list(HEALTH) + ["postgres"] if first.get(n) == "up"]
    return names, first.get("flink") == "RUNNING"


# The backlog moves by a few cases on its own (one scenario every 3 s, 2 cases per claim, 5 s poll).
BACKLOG_SLACK = 2


def recovered(row, needed, flink, baseline_max, analyses_at_heal):
    return (all(row.get(n) == "up" for n in needed)
            and (not flink or row.get("flink") == "RUNNING")
            and isinstance(row.get("analyses"), int) and row["analyses"] > analyses_at_heal
            and isinstance(row.get("backlog"), int) and row["backlog"] <= baseline_max + BACKLOG_SLACK)


def recovered_at(rows, meta, heal):
    """The first sample after the heal that meets the recovery condition (recomputed, so a re-report can fix it)."""
    after = [r for r in rows if parse_iso(r["t"]) >= heal]
    at_heal = next((r["analyses"] for r in after if isinstance(r.get("analyses"), int)), None)
    if at_heal is None:
        return None
    return next((r["t"] for r in after
                 if recovered(r, meta["needed"], meta["flink"], meta["baseline_backlog_max"], at_heal)), None)


def run(args):
    os.makedirs(args.out, exist_ok=True)
    start = now()
    marks, commands = {"start": iso(start)}, []
    sampler = Sampler(start, args.interval)
    sampler.start()
    print(f"[{args.name}] baseline {args.baseline} s", flush=True)
    time.sleep(args.baseline)
    baseline_rows = list(sampler.rows)
    baseline_max = max((r.get("backlog", 0) for r in baseline_rows), default=0)
    needed, flink = up_at_start(baseline_rows)

    marks["inject"] = iso(now())
    shell(args.inject, commands)
    if args.hold:
        print(f"[{args.name}] holding the fault {args.hold} s", flush=True)
        time.sleep(args.hold)
    if args.heal:
        marks["heal"] = iso(now())
        shell(args.heal, commands)
    else:
        marks["heal"] = marks["inject"]
    # The sample at the heal may come from a database that is down: recompute from all samples each time.
    condition = {"needed": needed, "flink": flink, "baseline_backlog_max": baseline_max}
    heal = parse_iso(marks["heal"])

    deadline = time.monotonic() + args.settle
    while time.monotonic() < deadline:
        time.sleep(args.interval)
        at = recovered_at(list(sampler.rows), condition, heal)
        if at:
            marks["recovered"] = at
            print(f"[{args.name}] recovered at {marks['recovered']}", flush=True)
            break
    else:
        print(f"[{args.name}] not recovered within {args.settle} s", flush=True)
    time.sleep(args.tail)
    sampler.stopped.set()
    sampler.join()
    marks["end"] = iso(now())

    meta = {"name": args.name, "marks": marks, "commands": commands, "baseline_backlog_max": baseline_max,
            "needed": needed, "flink": flink, "interval_s": args.interval}
    with open(os.path.join(args.out, "marks.json"), "w") as f:
        json.dump(meta, f, indent=2)
    write_samples(args.out, sampler.rows)
    report_dir(args.out, args.sim_log)
    shots(args.out)


def write_samples(out, rows):
    keys = []
    for row in rows:
        keys += [k for k in row if k not in keys]
    with open(os.path.join(out, "samples.csv"), "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=keys)
        writer.writeheader()
        writer.writerows(rows)


def read_samples(out):
    with open(os.path.join(out, "samples.csv")) as f:
        rows = list(csv.DictReader(f))
    for row in rows:
        for k, v in row.items():
            if v not in (None, "") and re.fullmatch(r"-?\d+", v):
                row[k] = int(v)
    return rows


def scenarios(sim_log, start, end):
    """Scenarios the simulator logged between start and end ("HH:mm:ss INFO Simulator - Injected X on t/a")."""
    if not sim_log or not os.path.exists(sim_log):
        return []
    local = start.astimezone()
    found = []
    pattern = re.compile(r"^(\d\d):(\d\d):(\d\d) .*Injected (\w+) on ([\w.-]+)/([\w.-]+)\s*$")
    with open(sim_log) as f:
        for line in f:
            m = pattern.match(line)
            if not m:
                continue
            h, mi, s = map(int, m.group(1, 2, 3))
            t = local.replace(hour=h, minute=mi, second=s, microsecond=0).astimezone(dt.timezone.utc)
            if t < start - dt.timedelta(hours=12):
                t += dt.timedelta(days=1)  # the drill ran past local midnight
            if start <= t <= end:
                found.append({"t": t, "scenario": m.group(4), "tenant": m.group(5), "account": m.group(6)})
    return found


def detected(scenario_list, end):
    """For each alerting scenario, the first alert of its rule on its account from a minute before the injection."""
    results = []
    for sc in scenario_list:
        rule = RULE_OF.get(sc["scenario"])
        if rule is None:
            continue
        rows = sql("""
            select min(received_at) from alert
            where tenant_id = :'tenant' and account_id = :'account' and rule = :'rule'
              and event_time >= (:'t')::timestamptz - interval '1 minute' and received_at <= :'end'
            """, tenant=sc["tenant"], account=sc["account"], rule=rule, t=iso(sc["t"]), end=iso(end))
        first = rows[0][0] if rows and rows[0] and rows[0][0] else None
        results.append({**sc, "rule": rule, "alert_received": first,
                        "expected_none": first is None and rule == "GEO_JUMP" and land_neighbours(sc)})
    return results


def land_neighbours(sc):
    """A GEO_JUMP whose two card-present payments are in neighbouring countries (Malaysia-Thailand) is a possible trip:
    no alert by design (ADR 0021)."""
    rows = sql("""
        select string_agg(distinct country, ',' order by country) from txn
        where tenant_id = :'tenant' and account_id = :'account' and channel = 'CARD_PRESENT'
          and (abs(extract(epoch from event_time - (:'t')::timestamptz)) < 3
               or abs(extract(epoch from event_time - ((:'t')::timestamptz - interval '20 minutes'))) < 3)
        """, tenant=sc["tenant"], account=sc["account"], t=iso(sc["t"]))
    return bool(rows and rows[0] and rows[0][0] in NEIGHBOURS)


NEIGHBOURS = {"MY,TH"}


def pct(values, q):
    if not values:
        return None
    values = sorted(values)
    return round(values[min(len(values) - 1, int(round(q * (len(values) - 1))))], 1)


def latency_by_phase(start, inject, heal, end):
    # From the alert's last payment (the one that tripped the rule; a GEO_JUMP alert's event time is the home payment
    # 20 minutes earlier) to the first report stored after the alert arrived.
    rows = sql("""
        select a.received_at,
               extract(epoch from min(ca.created_at)
                       - (select max(t.event_time) from txn t where t.tenant_id = a.tenant_id and t.txn_id = any(a.txn_ids)))
        from alert a
        left join case_analysis ca on ca.case_id = a.case_id and ca.created_at >= a.received_at
        where a.received_at between :'start' and :'end'
        group by a.alert_id, a.received_at, a.tenant_id, a.txn_ids
        """, start=iso(start), end=iso(end))
    phases = {"before": [], "during": [], "after": []}
    unanalysed = {"before": 0, "during": 0, "after": 0}
    for received, seconds in rows:
        t = parse_iso(received.replace(" ", "T"))
        phase = "before" if t < inject else "during" if t < heal else "after"
        if seconds:
            phases[phase].append(float(seconds))
        else:
            unanalysed[phase] += 1
    return {p: {"alerts": len(v) + unanalysed[p], "unanalysed": unanalysed[p], "p50_s": pct(v, 0.5),
                "p95_s": pct(v, 0.95), "max_s": pct(v, 1.0)} for p, v in phases.items()}


def tools_by_phase(start, inject, heal, end):
    """Tool calls of the analyses stored in each phase: how many, how many returned nothing (an unavailable system
    or an empty result), and their p95 duration."""
    rows = sql("""
        select case when ca.created_at < :'inject' then 'before' when ca.created_at < :'heal' then 'during'
                    else 'after' end,
               tc->>'tool', count(*),
               count(*) filter (where coalesce((tc->>'resultCount')::int, 0) = 0),
               round(percentile_cont(0.95) within group (order by (tc->>'durationMs')::numeric))
        from case_analysis ca, jsonb_array_elements(ca.tool_calls) tc
        where ca.created_at between :'start' and :'end'
        group by 1, 2 order by 2, 1
        """, start=iso(start), inject=iso(inject), heal=iso(heal), end=iso(end))
    return [{"phase": p, "tool": t, "calls": int(n), "empty": int(e), "p95_ms": int(float(d)) if d else None}
            for p, t, n, e, d in rows]


def report_dir(out, sim_log):
    with open(os.path.join(out, "marks.json")) as f:
        meta = json.load(f)
    rows = read_samples(out)
    marks = {k: parse_iso(v) for k, v in meta["marks"].items()}
    start, inject, heal, end = marks["start"], marks["inject"], marks["heal"], marks["end"]

    offsets = {}
    with_offsets = [r for r in rows if any(k.startswith("offset:") for k in r)]
    if with_offsets:
        first, last = with_offsets[0], with_offsets[-1]
        for key in last:
            if key.startswith("offset:") and isinstance(last[key], int) and isinstance(first.get(key), int):
                offsets[key[len("offset:"):]] = last[key] - first[key]
    window = {"start": iso(parse_iso(with_offsets[0]["t"]) if with_offsets else start),
              "end": iso(parse_iso(with_offsets[-1]["t"]) if with_offsets else end)}
    (txn_rows, alert_rows), = sql("""
        select (select count(*) from txn where ingested_at between :'start' and :'end'),
               (select count(*) from alert where received_at between :'start' and :'end')
        """, **window)
    # Exact loss check: transactions the simulator sent in [start, end - 30 s) by Kafka timestamp against those stored
    # with a send time in the same range; malformed messages are on the topic but never stored. A payment's send time
    # is its event time, except GEO_JUMP's home payment, sent 20 minutes after its event time (TrafficGenerator).
    # The last 30 s are left to drain.
    a, b = start, end - dt.timedelta(seconds=30)
    sent = records_between("txn.transactions.v2", a, b)
    malformed_in_range = sum(1 for s in scenarios(sim_log, a, b - dt.timedelta(seconds=1)) if s["scenario"] == "MALFORMED")
    (stored_by_event_time,), = sql("""
        select count(*) from txn
        where (event_time >= :'a' and event_time < :'b' and ingested_at - event_time < interval '19 minutes')
           or (ingested_at - event_time >= interval '19 minutes'
               and event_time + interval '20 minutes' >= :'a' and event_time + interval '20 minutes' < :'b')
        """, a=iso(a), b=iso(b))
    alerts_on_topic = records_between("risk.alerts.v2", a, b)
    (alerts_by_detection,), = sql("select count(*) from alert where detected_at >= :'a' and detected_at < :'b'",
                                  a=iso(a), b=iso(b))
    events = {t: int(n) for t, n in sql("""
        select type || coalesce('>' || to_stage, ''), count(*) from case_event
        where at between :'start' and :'end' group by 1 order by 1
        """, start=iso(start), end=iso(end))}
    failed = [r[0] for r in sql("""
        select distinct case_id from case_event where to_stage = 'FAILED' and at between :'start' and :'end'
        """, start=iso(start), end=iso(end))]

    alerting = detected(scenarios(sim_log, start, end - dt.timedelta(seconds=DETECTION_GRACE_S)), end)
    missed = [{"t": iso(s["t"]), "offset_s": round((s["t"] - inject).total_seconds()), "scenario": s["scenario"],
               "account": f"{s['tenant']}/{s['account']}"}
              for s in alerting if not s["alert_received"] and not s["expected_none"]]
    no_alert_by_design = sum(1 for s in alerting if s["expected_none"])
    alerting = [s for s in alerting if not s["expected_none"]]
    malformed = sum(1 for s in scenarios(sim_log, start, end) if s["scenario"] == "MALFORMED")

    after_heal = [r for r in rows if parse_iso(r["t"]) >= heal and isinstance(r.get("analyses"), int)]
    analyses_at_heal = after_heal[0]["analyses"] if after_heal else None
    first_analysis = next((r["t"] for r in after_heal if r["analyses"] > analyses_at_heal), None)
    backlogs = [r["backlog"] for r in rows if isinstance(r.get("backlog"), int)]
    restarts = [r.get("flink_restarts") for r in rows if isinstance(r.get("flink_restarts"), int)]

    def since(t):
        return round((parse_iso(t) - heal).total_seconds(), 1) if t else None

    meta["marks"]["recovered"] = recovered_at(rows, meta, heal)
    if meta["marks"]["recovered"] is None:
        del meta["marks"]["recovered"]
    else:
        marks["recovered"] = parse_iso(meta["marks"]["recovered"])

    summary = {
        "name": meta["name"],
        "marks": meta["marks"],
        "fault_held_s": round((heal - inject).total_seconds(), 1),
        "recovery": {
            "first_analysis_after_heal_s": since(first_analysis),
            "recovered_after_heal_s": since(meta["marks"].get("recovered")),
            "backlog_baseline_max": meta["baseline_backlog_max"],
            "backlog_max": max(backlogs, default=None),
            "flink_restarts": (restarts[-1] - restarts[0]) if restarts else None,
        },
        "detection": {
            "alerting_scenarios": len(alerting),
            "detected": len(alerting) - len(missed),
            "missed": missed,
            "no_alert_by_design": no_alert_by_design,
            "malformed_sent": malformed,
        },
        "llm": llm_log(start, heal, end),
        "messages": {
            "offsets": offsets,
            "txn_rows_stored": int(txn_rows),
            "alert_rows_stored": int(alert_rows),
            "window": window,
            "exact": {"from": iso(a), "to": iso(b), "txn_records_on_topic": sent,
                      "malformed_on_topic": malformed_in_range, "txn_rows_by_event_time": int(stored_by_event_time),
                      "txn_missing": sent - malformed_in_range - int(stored_by_event_time),
                      "alert_records_on_topic": alerts_on_topic, "alert_rows_by_detection": int(alerts_by_detection),
                      "alert_records_not_stored": alerts_on_topic - int(alerts_by_detection)},
        },
        "cases": {"events": events, "failed_case_ids": failed},
        "latency_payment_to_report": latency_by_phase(start, inject, heal, end),
        "tools": tools_by_phase(start, inject, heal, end),
    }
    with open(os.path.join(out, "summary.json"), "w") as f:
        json.dump(summary, f, indent=2)
    with open(os.path.join(out, "summary.md"), "w") as f:
        f.write(markdown(summary, meta))
    with open(os.path.join(out, "chart.svg"), "w") as f:
        f.write(chart(rows, marks, meta["name"]))
    platform_warnings(out, start, end)
    print(markdown(summary, meta))


def markdown(s, meta):
    r, d, m = s["recovery"], s["detection"], s["messages"]
    off = m["offsets"]
    lines = [f"# Drill {s['name']}", ""]
    for c in meta["commands"]:
        lines.append(f"- `{c['command']}` at {c['t'][11:19]} UTC, exit {c['exit']}")
    lines += ["", f"Fault held {s['fault_held_s']} s. First analysis {r['first_analysis_after_heal_s']} s after the heal; "
              f"recovered (all up, backlog <= {r['backlog_baseline_max'] + BACKLOG_SLACK}) after {r['recovered_after_heal_s']} s. "
              f"Backlog peak {r['backlog_max']}. Flink restarts: {r['flink_restarts']}.", "",
              f"Scenarios: {d['detected']}/{d['alerting_scenarios']} detected"
              + (f", missed: {', '.join(x['scenario'] + ' at ' + str(x['offset_s']) + ' s' for x in d['missed'])}"
                 if d["missed"] else "") + f"; {d['no_alert_by_design']} GEO_JUMP between land neighbours (no alert "
              f"by design); {d['malformed_sent']} malformed sent.", "",
              f"Model calls: main provider failed {s['llm']['main_provider_failures']}, fallback answered "
              f"{s['llm']['fallback_answers']}, no provider answered {s['llm']['no_provider_answered']}; last fallback "
              f"{s['llm']['last_fallback_after_heal_s']} s after the heal.", "",
              "| Topic | Offsets added |", "|---|---|"]
    lines += [f"| {t} | {n} |" for t, n in sorted(off.items())]
    lines += ["", f"Stored in the sampled window: {m['txn_rows_stored']} transactions (topic +"
              f"{off.get('txn.transactions.v2', '?')}), {m['alert_rows_stored']} alerts (topic "
              f"+{off.get('risk.alerts.v2', '?')}).", "",
              f"Exact, {m['exact']['from'][11:19]}-{m['exact']['to'][11:19]} UTC by timestamp: "
              f"{m['exact']['txn_records_on_topic']} transaction records ({m['exact']['malformed_on_topic']} malformed), "
              f"{m['exact']['txn_rows_by_event_time']} stored: **{m['exact']['txn_missing']} missing**. "
              f"{m['exact']['alert_records_on_topic']} alert records, {m['exact']['alert_rows_by_detection']} alerts "
              f"stored: {m['exact']['alert_records_not_stored']} records not stored (duplicates or dead letters).", "", "| Phase | Alerts | Not analysed | p50 s | p95 s | max s |",
              "|---|---|---|---|---|---|"]
    for phase, v in s["latency_payment_to_report"].items():
        lines.append(f"| {phase} | {v['alerts']} | {v['unanalysed']} | {v['p50_s']} | {v['p95_s']} | {v['max_s']} |")
    lines += ["", "| Tool | Phase | Calls | Empty | p95 ms |", "|---|---|---|---|---|"]
    lines += [f"| {t['tool']} | {t['phase']} | {t['calls']} | {t['empty']} | {t['p95_ms']} |" for t in s["tools"]]
    lines += ["", "Case events: " + ", ".join(f"{k} {v}" for k, v in s["cases"]["events"].items()),
              f"FAILED cases: {len(s['cases']['failed_case_ids'])}", ""]
    return "\n".join(lines)


def chart(rows, marks, name, width=900, height=320):
    """Backlog, and analyses and alerts per interval, with the drill's marks: a plain SVG, no library."""
    pts = [r for r in rows if isinstance(r.get("backlog"), int)]
    if len(pts) < 2:
        return "<svg xmlns='http://www.w3.org/2000/svg'/>"
    t0, t1 = parse_iso(pts[0]["t"]), parse_iso(pts[-1]["t"])
    span = max(1.0, (t1 - t0).total_seconds())
    left, right, top, bottom = 50, 20, 30, 40
    pw, ph = width - left - right, height - top - bottom

    def x(t):
        return left + pw * (parse_iso(t) - t0).total_seconds() / span if isinstance(t, str) else \
            left + pw * (t - t0).total_seconds() / span

    series = {"backlog": [(r["t"], r["backlog"]) for r in pts]}
    for key, label in (("analyses", "analyses"), ("alerts", "alerts")):
        series[label] = [(b["t"], b[key] - a[key]) for a, b in zip(pts, pts[1:])
                         if isinstance(a.get(key), int) and isinstance(b.get(key), int)]
    ymax = max(1, max(v for s in series.values() for _, v in s))
    colors = {"backlog": "#d85a30", "analyses": "#1d9e75", "alerts": "#7f77dd"}
    out = [f"<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 {width} {height}' font-family='sans-serif' "
           f"font-size='11'><rect width='100%' height='100%' fill='white'/>",
           f"<text x='{left}' y='18' font-size='13'>Drill {name}: backlog, and alerts and analyses per sample</text>"]
    for i in range(5):
        y = top + ph * i / 4
        out.append(f"<line x1='{left}' x2='{left + pw}' y1='{y:.1f}' y2='{y:.1f}' stroke='#eee'/>"
                   f"<text x='{left - 6}' y='{y + 4:.1f}' text-anchor='end' fill='#666'>{ymax * (4 - i) / 4:.0f}</text>")
    for label in ("inject", "heal", "recovered"):
        if label in marks and not (label == "heal" and marks["heal"] == marks["inject"]):
            mx = x(marks[label])
            out.append(f"<line x1='{mx:.1f}' x2='{mx:.1f}' y1='{top}' y2='{top + ph}' stroke='#999' "
                       f"stroke-dasharray='4 3'/><text x='{mx + 3:.1f}' y='{top + 10}' fill='#444'>{label}</text>")
    for label, points in series.items():
        path = " ".join(f"{x(t):.1f},{top + ph * (1 - v / ymax):.1f}" for t, v in points)
        out.append(f"<polyline fill='none' stroke='{colors[label]}' stroke-width='1.5' points='{path}'/>")
    for i, label in enumerate(series):
        out.append(f"<rect x='{left + i * 110}' y='{height - 16}' width='10' height='10' fill='{colors[label]}'/>"
                   f"<text x='{left + i * 110 + 14}' y='{height - 7}'>{label}</text>")
    minutes = int(span // 60)
    for m in range(0, minutes + 1, max(1, minutes // 8 or 1)):
        tx = left + pw * m * 60 / span
        out.append(f"<text x='{tx:.1f}' y='{top + ph + 14}' text-anchor='middle' fill='#666'>{m} min</text>")
    out.append("</svg>")
    return "\n".join(out)


def log_lines(start, end):
    """(time, line) of the platform's WARN and ERROR lines in the window (Boot's ISO timestamps)."""
    if not os.path.exists(PLATFORM_LOG):
        return []
    kept = []
    with open(PLATFORM_LOG, errors="replace") as f:
        for line in f:
            m = re.match(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d+[+-]\d\d:\d\d)\s+(WARN|ERROR)\b", line)
            if m and start <= parse_iso(m.group(1)) <= end:
                kept.append((parse_iso(m.group(1)), line.rstrip()))
    return kept


def llm_log(start, heal, end):
    """What the gateway logged: calls the main provider failed, answers from the fallback, and how long after the
    heal the fallback still answered (the circuit stays open for open-for after its last failure)."""
    lines = log_lines(start, end)
    failures = [t for t, line in lines if "LlmGateway" in line and "failed for" in line]
    fallbacks = [t for t, line in lines if "answered in place of" in line]
    unanswered = [t for t, line in lines if "LlmGateway" in line and "no provider" in line.lower()]
    last = max(fallbacks, default=None)
    return {"main_provider_failures": len(failures), "fallback_answers": len(fallbacks),
            "no_provider_answered": len(unanswered),
            "last_fallback_after_heal_s": round((last - heal).total_seconds(), 1) if last and last > heal else None}


def platform_warnings(out, start, end, limit=300):
    """WARN and ERROR lines the platform logged in the window."""
    kept = [line[:400] for _, line in log_lines(start, end)]
    with open(os.path.join(out, "platform-warnings.txt"), "w") as f:
        f.write("\n".join(kept[:limit]) + ("\n... (truncated)\n" if len(kept) > limit else "\n"))


def screenshot(url, path, width=1600, height=1000):
    profile = os.path.join(os.path.dirname(os.path.abspath(path)), ".chrome")
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", f"--user-data-dir={profile}",
                    f"--window-size={width},{height}", "--virtual-time-budget=20000", f"--screenshot={path}", url],
                   capture_output=True, timeout=90)
    subprocess.run(["rm", "-rf", profile])


def shots(out):
    """The Grafana dashboard over the drill's window, and for Flink drills its exceptions and checkpoints."""
    with open(os.path.join(out, "marks.json")) as f:
        meta = json.load(f)
    marks = {k: parse_iso(v) for k, v in meta["marks"].items()}
    frm = int((marks["start"] - dt.timedelta(minutes=1)).timestamp() * 1000)
    to = int((marks["end"] + dt.timedelta(minutes=1)).timestamp() * 1000)
    screenshot(f"{GRAFANA}?from={frm}&to={to}&kiosk&theme=light", os.path.join(out, "grafana.png"))
    if "flink" in meta["name"]:
        state = flink_state()
        if state.get("flink_job"):
            for page in ("exceptions", "checkpoints"):
                screenshot(f"{FLINK}/#/job/running/{state['flink_job']}/{page}", os.path.join(out, f"flink-{page}.png"),
                           height=900)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    r = sub.add_parser("run")
    r.add_argument("name")
    r.add_argument("--out", required=True)
    r.add_argument("--sim-log", required=True)
    r.add_argument("--inject", required=True)
    r.add_argument("--heal")
    r.add_argument("--hold", type=float, default=60)
    r.add_argument("--baseline", type=float, default=60)
    r.add_argument("--tail", type=float, default=30)
    r.add_argument("--settle", type=float, default=600)
    r.add_argument("--interval", type=float, default=5)
    p = sub.add_parser("report")
    p.add_argument("out")
    p.add_argument("--sim-log", required=True)
    s = sub.add_parser("shots")
    s.add_argument("out")
    args = parser.parse_args()
    if args.command == "run":
        run(args)
    elif args.command == "report":
        report_dir(args.out, args.sim_log)
    else:
        shots(args.out)


if __name__ == "__main__":
    sys.exit(main())
