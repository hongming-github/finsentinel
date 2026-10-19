# 0018. Avro contracts in a schema registry for the data path

- Status: accepted (phase 8 step A)
- Date: 2026-10-10

## Context

Transactions and alerts crossed Kafka as JSON, with the shape defined by the Java records in `common`. Nothing checked
a producer against its consumers: a renamed field in the simulator, a bank's adapter, or the Flink job would surface
as messages in a DLQ (or, worse, as nulls that pass), after deployment. Banks integrating their own core systems need a
contract they can read and test against, and a change process that refuses breaking changes before they ship.

## Decision

- **Avro schemas are the contract** (`contracts/src/main/avro`): `TransactionEvent` and `AlertEvent`, generated into
  SpecificRecords, with mappers to the common records (`Contracts`), so rule and platform code is unchanged. Amounts
  are `decimal(18,2)`: an amount with more decimals is refused, not rounded. Times are `timestamp-micros`, the
  precision Postgres keeps. Optional fields are unions with `null` and a null default; channels, rules and severities
  are enums.
- **New topics**: `txn.transactions.v2`, `risk.alerts.v2` (and their DLQ/DLT). A format change is a breaking change,
  so it gets new topics; from now on compatible changes evolve in place under the registry's rule. `case.events.v1`
  stays JSON for now (an outbox payload with its own consumers; next candidate).
- **Registry: Apicurio Registry 3.3.3**, kafkasql storage (schemas live in a Kafka topic, so no extra database and they
  survive restarts), port 8095. Clients use its **Confluent-compatible API** (`/apis/ccompat/v7`) and the Confluent
  wire format (magic byte 0, 4-byte schema id, Avro binary). The same bytes and client work against Confluent Schema
  Registry or Karapace, so the registry is replaceable without touching a message.
- **Our own small serde** (`AvroSerde`, `HttpSchemaRegistry`, about 250 lines with JDK HttpClient and Jackson 3)
  instead of Apicurio's Kafka serde: that pulls Vert.x 4.5 and Netty 4.1, which clash with Spring Boot 4's Netty 4.2
  and would bloat the Flink job jar. We need four calls: look up an id, fetch a schema by id, register, set
  compatibility.
- **Schemas are registered on purpose, never by producers** (`SchemaRegistration`: `./mvnw -pl contracts -am compile
  exec:java`). Every producer looks up its own schema's id at startup and **fails to start** if it is not
  registered. Auto-registration would let any producer publish any shape and make the registry a log rather than a
  gate. The platform only consumes, and since drill 9b (ADR 0026) its serdes (`AvroSerde.forReading`) need no
  registry to be built: it starts and serves its API while the registry is down, and its listeners retry until the
  registry answers. A consumer reads by the writer's id, so its own schema's registration guards nothing at startup.
- **Compatibility: BACKWARD_TRANSITIVE** per subject: a new schema must read everything written with every earlier
  version, so consumers upgrade first, then producers, and replaying a topic from the start always works.
- **Bad message vs registry outage**: a message that can never be read (not the wire format, unknown schema id, bytes
  that do not decode) is `InvalidMessageException` and goes to the DLQ/DLT. A registry that cannot answer is not the
  message's fault: the exception propagates, so Flink fails the task and restarts from the last checkpoint, and the
  platform's listener retries until it succeeds (without a limit since ADR 0026; before that it gave up after two
  retries and dead-lettered the record, which drill 07b caught). Good data is never discarded because a dependency was
  down. Schemas by id are cached
  for the life of the process, so a running consumer only needs the registry for ids it has not seen.
- **Where decoding happens**: Flink's source hands on raw bytes (`RawValue`), and `ParseTransactions` decodes, so an
  unreadable message is routed to the DLQ (as the original bytes) instead of failing the source. The platform's Kafka
  consumers and producer use byte arrays: listeners decode with the serde, dead-letter records keep the bytes that
  arrived, and the outbox sends JSON it has serialised itself.

## Evidence

- `ApicurioCompatibilityTest` (real Apicurio container): registration is idempotent; a new required field is refused
  (409) and stays unregistered; an optional field with a default is accepted, and a consumer on the current generated
  class reads a message written with the new version. Without the compatibility rule Apicurio accepts the breaking
  change and the test fails (checked by removing the rule).
- `AvroSerdeTest`: round trips, the wire header, extra decimals refused, unregistered producer fails at construction,
  garbage / unknown id / truncated / empty / null are invalid, a registry outage is not.
- `AvroWireTest` (stream job): good transactions parse, JSON, unknown-id and empty messages go to the DLQ side output
  byte for byte, a registry outage fails the task with nothing sent to the DLQ, alerts are written as registry Avro
  keyed by account.
- Platform integration tests run against an Apicurio container: transactions stored from Avro, garbage skipped; a
  v1-style JSON alert lands on `risk.alerts.v2.DLT` exactly as sent and does not block the next alert.
- End to end on the compose stack (packaged platform jar, Flink MiniCluster, simulator; no model calls): 2,136
  messages on `txn.transactions.v2`, of which 4 injected malformed and 1 hand-written JSON message; 2,131 transactions
  stored, the 5 bad ones in `txn.transactions.dlq.v2` byte for byte; 74 alerts on `risk.alerts.v2`, 74 stored, DLT
  empty, consumer lag 0. Restarting the registry kept both subjects and their BACKWARD_TRANSITIVE rule (kafkasql).
  With the registry stopped, the simulator refused to start ("schema registry ... unreachable") before sending
  anything.

## Consequences

- A bank's integration team gets `.avsc` files and a registry that tells them, before deployment, whether a change is
  safe. Breaking changes mean a new topic version and a migration plan, which is the conversation that should happen.
- Every service now depends on the registry at startup; running services tolerate an outage for schemas they have
  seen. The registry needs the same availability as Kafka (kafkasql makes it as durable as Kafka; run more than one
  instance in production).
- Messages are smaller (no field names): a typical transaction is 113 bytes against 289 as JSON (39%), an alert 165
  against 343 (48%). Not a goal here, but it comes free with the contract.
- Avro 1.12 instantiates only allow-listed classes when reading; the serde trusts exactly the generated classes of its
  own schema.
