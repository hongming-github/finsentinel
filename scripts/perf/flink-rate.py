#!/usr/bin/env python3
"""Samples the running Flink job's throughput and lag (phase 9B, ADR 0025). Standard library only.

One line per interval:
  in/s       transactions appended to the topic (Kafka end offsets, read with the CLI in the Kafka container)
  flink/s    transactions the job's source read (the change of its partitions' current offsets)
  rules/s    records into the rule operator (its numRecordsIn counter)
  lag        end offsets minus the source's current offsets, all partitions
  busy-max   the busiest subtask's busy time per second over source and rules (1000 = saturated)
  bp-max     the most back-pressured subtask's time per second
Rates come from counters, not from Flink's per-second meters, which average over a minute.

    scripts/perf/flink-rate.py [--url http://localhost:8082] [--interval 5] [--samples 0] [--kafka finsentinel-kafka-1]
"""
import argparse
import json
import math
import subprocess
import sys
import time
import urllib.request

TOPIC = "txn.transactions.v2"


def get(url):
    with urllib.request.urlopen(url, timeout=5) as response:
        return json.load(response)


def number(value):
    """A metric without samples reports "NaN" (a string)."""
    try:
        result = float(value)
    except (TypeError, ValueError):
        return 0.0
    return 0.0 if math.isnan(result) else result


def metrics(base, job, vertex, names, agg):
    rows = get(f"{base}/jobs/{job}/vertices/{vertex}/subtasks/metrics?get={','.join(names)}&agg={agg}")
    return {row["id"]: number(row.get(agg)) for row in rows}


def end_offsets(container):
    out = subprocess.run(["docker", "exec", container, "/opt/kafka/bin/kafka-get-offsets.sh", "--bootstrap-server",
                          "localhost:9092", "--topic", TOPIC], capture_output=True, text=True, check=True).stdout
    return sum(int(line.rsplit(":", 1)[1]) for line in out.split() if line.startswith(TOPIC))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8082")
    parser.add_argument("--interval", type=float, default=5)
    parser.add_argument("--samples", type=int, default=0, help="0 = until interrupted")
    parser.add_argument("--kafka", default="finsentinel-kafka-1", help="Kafka container with the CLI tools")
    args = parser.parse_args()

    jobs = [j for j in get(f"{args.url}/jobs/overview")["jobs"] if j["state"] == "RUNNING"]
    if not jobs:
        sys.exit("no running job")
    job = jobs[0]["jid"]
    vertices = {v["name"]: v["id"] for v in get(f"{args.url}/jobs/{job}")["vertices"]}
    source = next(v for name, v in vertices.items() if name.startswith("Source: transactions"))
    rules = next(v for name, v in vertices.items() if name.startswith("risk-rules"))
    prefix = "Source__transactions.KafkaSourceReader.topic." + TOPIC.replace(".", "_") + ".partition."

    def sample():
        # Partitions register their offset metrics once assigned, which can be after the job starts.
        offset_names = [m["id"] for m in get(f"{args.url}/jobs/{job}/vertices/{source}/subtasks/metrics")
                        if m["id"].startswith(prefix) and m["id"].endswith(".currentOffset")]
        # A partition's offset is reported by the one subtask that reads it, so max = that subtask's value.
        current = sum(metrics(args.url, job, source, offset_names, "max").values())
        rules_in = metrics(args.url, job, rules, ["numRecordsIn"], "sum").get("numRecordsIn", 0.0)
        load = {}
        for vertex in (source, rules):
            for name, value in metrics(args.url, job, vertex, ["busyTimeMsPerSecond",
                                                               "backPressuredTimeMsPerSecond"], "max").items():
                load[name] = max(load.get(name, 0.0), value)
        return time.monotonic(), end_offsets(args.kafka), current, rules_in, load

    print("time         in/s   flink/s   rules/s       lag  busy-max  bp-max", flush=True)
    previous = sample()
    taken = 0
    while args.samples == 0 or taken < args.samples:
        time.sleep(args.interval)
        now = sample()
        seconds = now[0] - previous[0]
        print("%s %8.0f %9.0f %9.0f %9.0f %9.0f %7.0f" % (
            time.strftime("%H:%M:%S"),
            (now[1] - previous[1]) / seconds,
            (now[2] - previous[2]) / seconds,
            (now[3] - previous[3]) / seconds,
            now[1] - now[2],
            now[4].get("busyTimeMsPerSecond", 0),
            now[4].get("backPressuredTimeMsPerSecond", 0)), flush=True)
        previous = now
        taken += 1


if __name__ == "__main__":
    main()
