#!/usr/bin/env bash

set -Eeuo pipefail

PROJECT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$PROJECT_DIR"

if [[ ! -f .env ]]; then
  cp .env.example .env
fi

if [[ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if ! java -version 2>&1 | head -n 1 | grep -q 'version "21'; then
  echo Java 21 is required to run API tests
  exit 1
fi

cleanup() {
  result=$?
  if [[ $result -ne 0 ]]; then
    docker compose logs --tail=100
  fi
  docker compose down
  exit "$result"
}
trap cleanup EXIT

docker compose up -d --build

expected_services=$(docker compose config --services | wc -l | tr -d ' ')
for attempt in {1..90}; do
  service_statuses=$(docker compose ps --format '{{.State}} {{.Health}}')
  running_services=$(printf '%s\n' "$service_statuses" | awk 'NF > 0 { count++ } END { print count + 0 }')
  if [[ $running_services -eq $expected_services ]] &&
      printf '%s\n' "$service_statuses" | awk '$1 != "running" || $2 != "healthy" { exit 1 }'; then
    break
  fi
  if [[ $attempt -eq 90 ]]; then
    echo Docker services did not become healthy in time
    exit 1
  fi
  sleep 2
done

for attempt in {1..90}; do
  if curl --fail --silent --show-error http://localhost:8080/health >/dev/null; then
    break
  fi
  if [[ $attempt -eq 90 ]]; then
    echo API stack did not become healthy in time
    exit 1
  fi
  sleep 2
done

mvn -f services/pom.xml \
  -s scripts/api-tests-maven-settings.xml \
  -P e2e-tests \
  -pl e2e-tests \
  -am test \
  -DskipE2eTests=false
