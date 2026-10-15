# 0022. CI quality and security gates

- Status: accepted
- Date: 2026-10-15

## Context

CI ran one job, `./mvnw verify`: compile and every test that calls no model. Nothing checked code beyond what tests
exercise, the rules in CLAUDE.md were kept by discipline alone, dependencies were never checked for known
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
  lifecycle plugins Maven had been defaulting), no duplicate dependency declarations, and two CLAUDE.md rules as
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
example; minor and patch updates grouped per ecosystem, majors one by one.

### Known vulnerabilities at the start

The first scan found 47 HIGH/CRITICAL findings (23 CVEs), all fixed upstream: Tomcat 11.0.24 (3 CRITICAL; Spring Boot
4.1.1, the newest, still manages it), Netty 4.2.13, Jackson 3.1.5, Jackson 2 in four versions brought in by Spark,
Flink, Avro and Boot, and lz4-java. They are listed in `.trivyignore.yaml`, each limited to the package versions it
was found in (the same CVE in a new place still fails) and expiring on 2026-11-14, after which the scan fails again.
Upgrading them is separate work: overriding Boot-managed versions and Jackson under Spark needs the full suite and a
packaged-jar run.

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

## Consequences

- A local `./mvnw verify` is the build job; compile is slower by the Error Prone pass.
- The CycloneDX plugin logs a few warnings of its own (BOM-imported entries nothing depends on, schema keywords its
  validator does not know); it has no switch for them. Compiler and Error Prone warnings are what fail the build.
- Adding a dependency to a Spring-free module, a primitive tool parameter or a direct `ChatClient` call now fails the
  build with the rule's reason.
- Someone has to act within 30 days on the exceptions, and on Dependabot's PRs every week.
- Turning on Dependabot alerts and security updates is a repository setting (free for private repositories); it is
  the owner's to switch on.
