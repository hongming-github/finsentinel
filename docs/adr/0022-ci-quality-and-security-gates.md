# 0022. CI quality and security gates

- Status: accepted
- Date: 2026-10-15

## Context

CI ran one job, `./mvnw verify`: compile and every test that calls no model. Nothing checked code beyond what tests
exercise, the project's architecture rules were kept by discipline alone, dependencies were never checked for known
vulnerabilities, and the workflows themselves ran third-party actions by movable tags with the default token
permissions. The repository is private without GitHub Advanced Security, so CodeQL and the dependency-review action
are not available; the checks below are free and open source.

## Decision

**Build job** (`./mvnw verify`, so every local build runs the same checks):

- **javac lint as errors**: `-Xlint:all` with `failOnWarning`, except three categories that only flag idioms or the
  classpath here: `serial` (Java serialization only ships Flink functions within one build, never stored data; Flink
  state has its own serializers, see `SerializationTest`), `try` (scope-style try-with-resources) and `processing` (a
  test dependency's annotation processor). `-Dmaven.compiler.failOnWarning=false` lists every warning at once.
- **Error Prone 2.50.0** in javac, its warnings failing the build too. Off: `MissingSummary` (records document
  themselves with `@param` only) and `StringSplitter` (the chunker relies on `String.split`'s documented behaviour and
  its output is pinned by `RetrievalEvalTest`; changing it would need a paid re-run). Generated Avro code is excluded.
  Needs the javac exports in `.mvn/jvm.config`.
- **Maven Enforcer**: Java 21+, Maven 3.9+, every plugin version pinned (`requirePluginVersions`, which pinned the six
  lifecycle plugins Maven had been defaulting), no duplicate dependency declarations, and two architecture rules as
  dependency bans, switched on per module: `no-spring` (contracts, telemetry, txn-simulator, stream-job, batch-job;
  ADR 0001) and `no-runtime-dependencies` (common).
- **ArchUnit** tests for rules the compiler cannot see: every model call goes through `LlmGateway` (no `ChatClient`
  or `ChatModel` outside `platform.llm`, ADR 0012); Jackson 3 only; `@Tool` methods take no primitive parameters
  (the model sends null); no `ToolCallbackProvider` beans or implementations (Spring AI would hand them to the agents);
  graph state only through our fenced saver, never the library's savers (ADR 0009); Flink-free rule logic in
  `stream-job`. The ArchUnit core library with plain JUnit tests, not its JUnit engine, whose support for JUnit 6 is
  not established.
- **CycloneDX SBOM** (`target/bom.json`, 463 components) of what Maven resolved, kept as a build artifact.

**Dependency scan** (needs the build's SBOM): Trivy scans the SBOM and fails on HIGH or CRITICAL vulnerabilities with a
fix. Scanning the SBOM rather than the POMs means the versions Maven actually resolved, BOMs and overrides included.

**Repository scan** (parallel): Trivy over the checkout for secrets, misconfigurations and vulnerable lock files (the
Python example's `uv.lock`). POMs are skipped and `--offline-scan` set: in POM mode Trivy resolves dependencies against
Maven Central itself and was rate-limited (429) on the first local run.

**Workflow lint** (parallel): actionlint, with shellcheck on every `run:` script.

**Hardening of both workflows**: `permissions: contents: read`; `persist-credentials: false` on checkout; actions pinned
by commit SHA with the version in a comment, tool images pinned by digest; one run per branch (`concurrency`); the
evaluation workflow's inputs pass through environment variables instead of being spliced into the script (a case id
such as `x; curl ...` was a script injection).

**Dependabot** proposes weekly updates for Maven, GitHub Actions (SHAs included), the compose images and the Python
example; minor and patch updates grouped per ecosystem, majors one by one. Maven libraries whose version is chosen to
match a framework (see "Upgrades") come in a group of their own, so one decision does not hold back the safe updates;
OpenTelemetry is ignored, since it moves only with Spring Boot. The catch-all group excludes those patterns
explicitly: without that, Dependabot put every dependency in the catch-all (its job log: "belongs to more specific
group"), not in the first group that matched.

### Known vulnerabilities at the start

The first scan found 47 HIGH/CRITICAL findings (23 CVEs), all fixed upstream: Tomcat 11.0.24 (3 CRITICAL; Spring Boot
4.1.1, the newest, still manages it), Netty 4.2.13, Jackson 3.1.5, Jackson 2 in four versions brought in by Spark,
Flink, Avro and Boot, and lz4-java. They are listed in `.trivyignore.yaml`, each limited to the package versions it
was found in (the same CVE in a new place still fails) and expiring on 2026-11-14, after which the scan fails again.
Upgrading them was separate work, done the next day (below); the exception file is empty now.

### Upgrades (2026-10-16)

Every fixed version was checked on Maven Central and released at least two weeks before (on or before 2026-10-01):

| Library | Was | Now | Where it came from |
|---|---|---|---|
| Tomcat (`tomcat-embed-core`, `-el`, `-websocket`) | 11.0.24 | 11.0.26 (2026-09-09) | Spring Boot 4.1.1, the newest Boot; platform and both MCP servers |
| Jackson 3 (`tools.jackson` BOM) | 3.1.5 | 3.1.7 (2026-09-21) | root and Boot |
| Jackson 2 (`com.fasterxml.jackson` BOM) | 2.18.2, 2.21.2, 2.21.5, 2.22.1 | 2.21.7 (2026-09-21) | Flink Kafka connector, Spark, Boot, Avro |
| Netty (BOM) | 4.2.13 | 4.2.17 (2026-08-04) | Spark (Boot already managed 4.2.17) |
| lz4-java | 1.10.1, 1.11.0 | 1.11.4 (2026-09-25) | kafka-clients 4.2.1, Spark |

Spring Boot 4.1.2 (2026-10-31) manages Tomcat 11.0.27, the version the override had reached, so the Tomcat override is
gone from the platform and both MCP servers.

- **Overrides go ahead of the Boot BOM.** Imported BOMs do not override each other: the first one listed wins, and a
  module's own imports come before those it inherits. A test showed it: with `jackson3.version` raised in the root,
  `contracts` resolved 3.1.7 and `agent-platform` still 3.1.5. So the three Spring modules import the Jackson BOMs
  and pin Tomcat (which has no BOM) before `spring-boot-dependencies`, from version properties in the root; plain
  `dependencyManagement` entries in the root (lz4) win everywhere, as `kafka-clients` already did. Each override is
  to be dropped once Boot manages a fixed version.
- **Jackson 2 on one version, the 2.21 line.** Spark checks that `jackson-module-scala` matches databind's minor
  version, and Spark 4.2.0 and Boot 4.1.1 are both on 2.21; the Jackson 2 BOM moves the Scala module with databind.
  Avro 1.12.2 asks for 2.22.1 but already ran on 2.18.2 in the Flink job and on 2.21.2 under Spark (Maven's nearest
  wins); the contracts tests pass on 2.21.7.
- **Jackson 3 stays on Boot's 3.1 line** (3.1.7, not Dependabot's 3.2.3): Boot 4.1.1 manages 3.1.5, so a patch
  release is the smallest step that fixes the CVEs; the move to 3.2 belongs with a Boot upgrade that manages it.
- **Spark and Flink are `provided`.** The overrides fix what we test and run locally and what the SBOM lists; on a real
  cluster the cluster's own jars apply (Spark 4.2.0 ships Netty 4.2.13 and Jackson 2.21.2). A deployment needs a
  Spark release with those fixes or the same overrides in the job's classpath. Shaded copies inside Flink
  (`flink-shaded-jackson` 2.18.2, `flink-shaded-netty` 4.1.100) carry their own coordinates, so the SBOM scan does not
  map them to Jackson or Netty advisories: a blind spot of SBOM scanning, not a clean result.
- **Dependabot's grouped PR #9** (it replaced #8 after #1 and #2 were merged; 22 updates, red): taken are slf4j
  2.0.20 and the Maven plugins compiler 3.16.0, surefire and failsafe 3.6.0, shade 3.6.2, exec 3.6.4 (build green,
  no new warnings). Left, with the reason:
  - OpenTelemetry 1.67.0: must equal Boot's managed 1.62.0 (ADR 0020); 1.67 deprecates an API `TraceContext` uses,
    which is what turned the PR red. Now ignored in `dependabot.yml`.
  - Jackson 3.2.3: see above. Kafka 4.4.0: released 2026-10-06 (less than two weeks), and Boot 4.1.1 and
    spring-kafka manage the 4.2 line; 4.2.2 still brings lz4 1.11.2, so it fixes nothing here. JUnit 6.1.3: Boot
    manages 6.0.3 in the Spring modules, and one JUnit across modules is worth more than a minor. log4j 2.26.1: the
    POM keeps log4j aligned with Spark 4.2.0's 2.25 line, and 2.25.5 has the fix that alerted. MCP SDK 2.0.1: Spring
    AI 2.0.1 is built on 2.0.0 (its `mcp-core` stays 2.0.0, so a bump would split the SDK).
  - Flink 2.3.0: no `flink-connector-kafka` release for Flink 2.3 on Maven Central yet (latest `5.0.0-2.2`), and a
    Flink minor needs its own savepoint check of the stateful operators. Staying on 2.2.1 changes nothing for
    savepoints; the Jackson 2 change does not touch Flink state either (state types go through Flink's POJO
    serializers, `SerializationTest`).
  - Maven 3.10.0: its resolver fetches `.meta/prefixes.txt` from every repository that dependency POMs declare, so
    every build logged four warnings (Flyway's and Redgate's feeds answer 401, java.net's certificate has expired).
    Worth its own change: deciding how to filter or block those repositories.

### Upgrades (2026-10-28)

Advisories published after the last green run on `main` (2026-10-26) failed both scans with no code change:

| Library | Was | Now | Where it came from | Advisory |
|---|---|---|---|---|
| snappy-java | 1.1.10.7, 1.1.10.8 | 1.1.10.10 (2026-10-03) | kafka-clients 4.2.1 (simulator, stream job, platform tests), Spark | CVE-2026-108106, CVE-2026-93451 (HIGH) |
| Guava | 32.0.0-jre | 33.7.2-jre (2026-09-29) | Spark, through `curator-client` (`batch-job`, provided) | CVE-2026-102554 (HIGH) |
| certifi (Python example) | 2026.7.22 | 2026.10.23 | `httpx`, `requests`, `langchain-openai` (`uv.lock`) | GHSA-6q48-6c7w-9gw2 (CRITICAL) |

- **Root `dependencyManagement` entries, no Spring-module overrides.** Spring Boot 4.1.1 manages neither artifact (its
  `guava` entry is Caffeine's adapter), so plain entries in the root win in every module, as lz4's do; the platform's
  test classpath resolves snappy-java 1.1.10.10 too.
- **snappy-java 1.1.10.10, not 1.1.10.9 or the newest.** 1.1.10.9 is the first fixed release; 1.1.10.10 followed the
  same day with a fix for a double release of stream buffers in the code 1.1.10.9 changed. 1.1.10.12 changes the
  Hadoop stream header size, a behaviour change with no security fix.
- **Guava 32 to 33** stays inside `batch-job`'s provided Spark classpath (Spark uses its own shaded copy); on a cluster
  Spark's own jars apply, as for Netty and Jackson above.
- **certifi breaks the two-week rule**: the first fixed release (2026.10.18) is ten days old, and a CRITICAL finding in
  the example's lock file does not wait (the advisory: the bundle trusted compromised emSign root CAs).
  `uv lock --upgrade-package certifi` took the newest, 2026.10.23, a CA bundle with no code; the example's other
  packages are unchanged.
- **Checked locally** with the workflow's Trivy image and commands: the SBOM scan and the repository scan report 0
  findings; the same repository scan with the old `uv.lock` still fails on certifi. `clean verify` passes.

### Why not

- **A formatter (Spotless)**: it would rewrite nearly every file for no reduction in risk; `ratchetFrom` could enforce
  it on changed files later.
- **OWASP dependency-check**: needs an NVD API key, is slow and flaky; Trivy with the GitHub advisory data covers it.
- **SpotBugs with FindSecBugs**: a long first triage of false positives for little beyond Error Prone and Trivy.
- **trivy-action**: its tags were moved in a supply-chain attack in March 2026; running the official image by digest
  removes one layer of third-party code from the runner.

## Evidence

- Lint: 29 warnings at first, all in the three disabled categories. Error Prone: 77 findings; 34 `MissingSummary`, 6
  `StringSplitter`, the rest fixed or suppressed one by one with the reason at the site. Two were real:
  - `CaseWorker` dropped the futures of its analysis tasks, so a failure outside the analysis's own handling (the
    database gone while recording a failure, or before the span opened) vanished without a log line; it now logs each
    failed task after the executor closes.
  - `AuditLogTest`'s concurrent appends dropped their futures, so a failed append could only show up indirectly; the
    test now rethrows it. The Kafka integration tests now wait for each send.
- Each gate was seen failing on a planted violation before being trusted: a `new Date()` (Error Prone), a compile
  dependency in `common` and Spring in `stream-job` (enforcer), a primitive `@Tool` parameter (ArchUnit), an unknown
  step id (actionlint), the SBOM without the exception file (Trivy, exit 1).
- Upgrades, 2026-10-16, Trivy image of `ci.yml`, vulnerability database of 2026-10-15 13:13 UTC: the SBOM of the
  previous commit scanned without exceptions still gives 47 findings (23 CVEs), so the database sees them; the new
  SBOM (437 components, was 463: one Jackson 2 and one lz4 version instead of four and two) gives none at HIGH or
  CRITICAL, nor at MEDIUM or LOW. Every exception was removed.
- After the upgrades: `./mvnw clean verify` green with no compiler or Error Prone warnings (the shade plugin's new
  overlap warning, Jackson 2.21 and 3 shipping the same two licence files, is merged like the one before); the
  packaged platform and both MCP servers start on Tomcat 11.0.26 and answer a Keycloak-authenticated case query (200,
  another bank 404, no token 401). An isolated end-to-end run on a throwaway Kafka and lake (the shared dev topics
  were left alone): simulator 4,818 transactions, the Flink 2.2.1 job (MiniCluster) 17 alerts, 2 malformed messages
  to the DLQ and landing files, no exception; then the batch job (Spark local mode) curated 1,703 lines and published
  200 account baselines.

## Consequences

- A local `./mvnw verify` is the build job; compile is slower by the Error Prone pass.
- The CycloneDX plugin logs a few warnings of its own (BOM-imported entries nothing depends on, schema keywords its
  validator does not know); it has no switch for them. Compiler and Error Prone warnings are what fail the build.
- Adding a dependency to a Spring-free module, a primitive tool parameter or a direct `ChatClient` call now fails the
  build with the rule's reason.
- Someone has to act on Dependabot's PRs every week; an exception, if one is ever needed again, expires within 30 days.
- The overrides in the Spring modules hold versions Boot does not manage: each Boot upgrade should remove the ones it
  makes unnecessary, or they will start holding versions back.
- Turning on Dependabot alerts and security updates is a repository setting (free for private repositories); it is
  the owner's to switch on.
