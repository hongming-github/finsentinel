# 0019. Daily account baselines from a Spark batch over a landing zone written by Flink

- Status: accepted (phase 8 step B)
- Date: 2026-10-10

## Context

The large-amount rule judges a payment against the account's own history, kept in Flink state as a running mean and
variance (Welford). That history exists only while the state does: a job started without a savepoint (a new
deployment, a lost checkpoint), or an account quiet for longer than the state TTL (30 days), starts from nothing, and
until it has seen 10 transactions only the hard limit (50,000) applies. A fraudster draining a dormant account is the
case where that matters most. The bank's history is complete in Kafka and in our own data, but not in a form a
streaming operator can use on its first event.

## Decision

- **Flink writes a raw landing zone** (`LandingZone`): every readable transaction, one JSON line each, under
  `landing/transactions/dt=YYYY-MM-DD/` by UTC event date (a late event lands in its own day). A `FileSink` on a
  local or any Flink file system path, no Hadoop in the job; files roll on checkpoints, so a file the batch can see is
  complete and a restart from a checkpoint does not write it twice (in-progress files start with a dot, which Spark
  ignores). JSON Lines rather than Avro or Parquet: the landing zone is the raw, append-only record, readable by
  anything, and the batch owns the curated format.
- **A Spark job owns the rest** (`batch-job`, Spark 4.2.0, no Spring):
  - *Curation*: re-reads the last `curate.lookback-days` (3) landing days with an explicit schema; lines that do not
    parse, lack a key field or have an unknown channel go to `quarantine/` with the run date; duplicates by (tenant,
    transaction id) are dropped; the result is Parquet under `curated/transactions/dt=`. Only the days written are
    overwritten (dynamic partition overwrite), so a re-run gives the same result and late events inside the lookback
    are picked up.
  - *Baselines*: per account over the `baseline.window-days` (90) complete UTC days before `as-of`: sample size,
    median, 1.4826 x median absolute deviation (MAD), mean, p95, max. **Robust centre and spread** because the batch
    cannot see which past amounts were fraud: one 50,000 payment among twenty around 100 leaves the median at 100 and
    the robust spread at 7.4, while the mean goes to 2,476 (tested). Streaming state can leave flagged amounts out;
    the batch cannot, so it does not depend on them.
  - *Output*: a Parquet snapshot per run (`gold/account_baselines/as_of=`), and every baseline published as Avro
    (`AccountBaselineEvent`, a new subject under the BACKWARD_TRANSITIVE rule of ADR 0018) to `account.baselines.v1`,
    keyed tenant|account, **compacted** (the latest per account stays, so a restarted job rebuilds from it). Published
    from the executors with a plain producer and our serde, waiting for every acknowledgement: a failed send fails the
    task and Spark retries it. Re-publishing is harmless (below).
- **Flink reads the baselines back** on a second source and `connect`s them with the transactions, **keyed by
  tenant|account** (not broadcast: each task holds only its own accounts' baselines, so state grows with accounts,
  not with parallelism). The baseline goes into the same `AccountRiskState`; an older baseline arriving late (a re-run
  of a past day) does not replace a newer one.
- **The baselines source starts from the beginning of the topic** whenever the job starts without a savepoint
  (`OffsetsInitializer.earliest()`), while transactions continue from the group's committed offsets. Restoring from a
  checkpoint or savepoint resumes both from the offsets in the state. (Fixed on 2026-10-15: at first both used the
  group's committed offsets, so a redeployed job on the same group skipped every baseline the old job had read and
  had none until the next batch run; see Evidence.)
- **Which profile the rule uses**: the account's streaming history once it has `min-history` (10) transactions, since
  it is more recent and excludes flagged amounts; otherwise a batch baseline with at least `min-history` transactions
  and no older than `rules.large-amount.baseline-max-age` (3 days, from the transaction's event time); otherwise only
  the hard limit. The alert says which one it used ("... the account median of 200.05 over 42 transactions (batch
  baseline as of 2026-10-10)"), so an analyst and the agents can see it.
- **Days are UTC everywhere** (landing directories, curated partitions, the window), set in the Spark session.
- **Operators have stable uids** now (`uid("risk-rules")` etc.), so the rule state survives this and later topology
  changes when the job is restored from a savepoint.
- Scheduling is not built: run `BaselineJob` once a day after midnight UTC with any scheduler (cron, Airflow, a
  Kubernetes CronJob). Re-running a day is safe.

## Evidence

- Unit tests: curation quarantines a broken line, a line without an amount and an unknown channel, drops a duplicate,
  and gives the same counts and files on a second run; a baseline over a known sample matches hand-computed values
  (median 100, MAD 5 x 1.4826, mean 52,000/21) and leaves out transactions on the as-of day and before the window;
  publishing to a Kafka container yields one Avro record per account with the right key; rule tests cover the
  fallback, a stale baseline (7 days), a thin one (5 transactions), streaming history winning, and a late older
  baseline. `SerializationTest` caught a `LocalDate` field that Flink would have serialised with Kryo: the common
  record carries `asOf` as an `Instant` (midnight UTC), the Avro contract as a `date`.
- End to end on the compose stack (no model calls): Flink replayed the topic plus a simulator backfill into 5,231
  landing lines in 8 day directories (all committed, matching the topic). Spark in local mode took 14.6 s: 3,929 lines
  read (today excluded by design), 0 quarantined, 0 duplicates, 100 baselines; a second run was identical. Landing
  1,173,795 bytes became 258,998 bytes of Parquet (22%) for the same rows. A Flink job started with no state and
  reading transactions from the end consumed the 200 baseline records; then, for `harbour-acc-0042` (42 transactions,
  median 200.05): a payment of 200.05 raised nothing, 6,001.50 raised LARGE_AMOUNT citing the batch baseline, and the
  same 6,001.50 on an account with no baseline raised nothing (below the hard limit). Without the baseline, the fresh
  job would have let the 6,001.50 through.
- Cold start on a group that had already read both topics (fixed 2026-10-15): `StartingOffsetsTest` commits the
  group's offsets at the end of both topics in a Kafka container and reads each source the job builds: transactions
  yield nothing (they continue), baselines yield every record; with the earlier `committedOffsets` for baselines it
  read none and the test fails. On the compose stack, the same redeployment (group moved to the end of both topics,
  fresh baselines as of the day): before the fix a payment 30x the account median (2,407.50 against 80.25 over 95
  transactions) raised nothing; after it, LARGE_AMOUNT citing the batch baseline. The first e2e had used a new group,
  which starts at the beginning anyway, so it did not show this.
- Found on the way: the first run wrote 82 Parquet files and opened 82 Kafka producers for 100 baselines, because AQE
  does not coalesce a cached plan's shuffle partitions by default; with
  `spark.sql.optimizer.canChangeCachedPlanOutputPartitioning` it writes one file.

## Consequences

- A redeployed or restored job no longer starts blind for accounts with history; dormant accounts keep a profile as
  long as they have transactions in the 90-day window (a baseline update counts as activity for the state TTL).
- The batch is a day behind by design: today's transactions are in tomorrow's baseline. The streaming history covers
  the gap once it has enough events.
- Two writers of "normal" now exist (streaming Welford, batch robust stats) with different statistics; the alert
  names the source, and the thresholds (`std-devs`, `mean-multiple`) apply to both. Tuning them per source would need
  labelled data.
- The landing zone grows without bound; retention (and moving it to object storage) is an operational decision left
  for deployment. Curated and gold data can always be rebuilt from it.
- The agents do not read the baselines yet; an investigation tool that shows the 90-day profile is the obvious next
  use.
