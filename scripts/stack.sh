#!/usr/bin/env bash
# The whole local stack for load tests and chaos drills (phase 9, ADR 0024): infrastructure in Docker, then fake-llm,
# the two MCP servers and the platform (profile "load": every model call goes to fake-llm, so nothing costs money).
# Detection and traffic are separate: see "flink" and "traffic" below.
#
#   scripts/stack.sh up        start what is not running; waits until each process answers
#   scripts/stack.sh status    what is up
#   scripts/stack.sh down      stop the processes this script started (Docker keeps running; "down --all" stops it too)
#   scripts/stack.sh flink     the Flink job in an embedded MiniCluster, in the foreground (web UI :8082)
#   scripts/stack.sh traffic [args]   the simulator in the foreground, e.g. --tps=50 --fraud-every=2
#   scripts/stack.sh console   the web console in the foreground (http://localhost:8097, ADR 0027; needs Node)
#
# Logs and pid files go to target/stack/. Needs Docker, JDK 21 and a prior ./mvnw install -DskipTests.
set -euo pipefail
cd "$(dirname "$0")/.."

RUN=target/stack
COMPOSE=(docker compose -f deploy/docker-compose.yml --profile core --profile systems --profile obs)
mkdir -p "$RUN"

# Traces of the MCP servers to the obs collector, so a case's trace in Tempo reaches the bank systems (the platform's
# profile "load" sets its own, logs included).
TRACES=--management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:4318/v1/traces

# name|command|health URL
PROCESSES=(
  "fake-llm|./mvnw -q -pl fake-llm exec:java|http://localhost:8096/health"
  "mcp-banking|java -jar mcp-banking/target/mcp-banking-0.1.0-SNAPSHOT.jar $TRACES|http://localhost:8091/actuator/health"
  "mcp-compliance|java -jar mcp-compliance/target/mcp-compliance-0.1.0-SNAPSHOT.jar $TRACES|http://localhost:8092/actuator/health"
  "platform|java -jar agent-platform/target/agent-platform-0.1.0-SNAPSHOT.jar --spring.profiles.active=load|http://localhost:8090/actuator/health"
)

running() {
  local pid_file="$RUN/$1.pid"
  [[ -f "$pid_file" ]] && kill -0 "$(cat "$pid_file")" 2>/dev/null
}

wait_for() {
  local name=$1 url=$2
  for _ in $(seq 1 120); do
    if curl -fs -o /dev/null "$url"; then
      echo "up    $name"
      return 0
    fi
    if ! running "$name"; then
      echo "died  $name (see $RUN/$name.log)" >&2
      return 1
    fi
    sleep 1
  done
  echo "slow  $name: no answer from $url after 120 s (see $RUN/$name.log)" >&2
  return 1
}

up() {
  "${COMPOSE[@]}" up -d
  ./mvnw -q -pl contracts exec:java >"$RUN/schemas.log" 2>&1 || { echo "schema registration failed: $RUN/schemas.log" >&2; exit 1; }
  for entry in "${PROCESSES[@]}"; do
    IFS='|' read -r name command url <<<"$entry"
    if running "$name"; then
      echo "have  $name"
      continue
    fi
    # shellcheck disable=SC2086 # the command is split into words on purpose
    nohup $command >"$RUN/$name.log" 2>&1 &
    echo $! >"$RUN/$name.pid"
    wait_for "$name" "$url"
  done
  echo "Grafana: http://localhost:3000 (dashboard 'FinSentinel pipeline'); fake-llm behaviour: http://localhost:8096/admin/behaviour"
}

status() {
  for entry in "${PROCESSES[@]}"; do
    IFS='|' read -r name _ url <<<"$entry"
    if curl -fs -o /dev/null "$url"; then echo "up    $name"; else echo "down  $name"; fi
  done
}

down() {
  # Every process with a pid file here, including a Flink job or simulator someone started in the background.
  for pid_file in "$RUN"/*.pid; do
    [[ -e "$pid_file" ]] || continue
    name=$(basename "$pid_file" .pid)
    if running "$name"; then
      # The process group: "./mvnw exec:java" starts a JVM of its own.
      pkill -TERM -P "$(cat "$RUN/$name.pid")" 2>/dev/null || true
      kill "$(cat "$RUN/$name.pid")" 2>/dev/null || true
      echo "stop  $name"
    fi
    rm -f "$pid_file"
  done
  if [[ "${1:-}" == "--all" ]]; then
    "${COMPOSE[@]}" down
  fi
}

case "${1:-}" in
  up) up ;;
  status) status ;;
  down) shift; down "${1:-}" ;;
  flink) ./mvnw -pl stream-job exec:exec ;;
  traffic) shift; ./mvnw -pl txn-simulator exec:java -Dexec.args="$*" ;;
  console) cd console && { [[ -d node_modules ]] || npm ci; } && npm run dev ;;
  *) sed -n '2,14p' "$0"; exit 1 ;;
esac
