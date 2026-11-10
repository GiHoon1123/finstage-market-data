#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
container="finstage-migration-mysql-${RANDOM}"
cleanup() { docker rm -f "$container" >/dev/null 2>&1 || true; }
trap cleanup EXIT
# A disposable database; no original credentials or persisted volumes are used.
docker run --detach --name "$container" --publish 127.0.0.1::3306 \
  --env MYSQL_ROOT_PASSWORD=migration-test-only \
  --env MYSQL_DATABASE=finstage_migration_test mysql:8.0 >/dev/null
ready=0
for attempt in $(seq 1 90); do
  if docker exec "$container" mysqladmin ping -uroot -pmigration-test-only --silent >/dev/null 2>&1; then ready=1; break; fi
  sleep 1
done
if [ "$ready" -ne 1 ]; then echo 'MySQL did not become ready' >&2; exit 1; fi
port="$(docker port "$container" 3306/tcp | awk -F: '{print $NF}')"
MIGRATION_TEST_DATABASE_URL="jdbc:mysql://127.0.0.1:${port}/finstage_migration_test?allowPublicKeyRetrieval=true&useSSL=false" \
MIGRATION_TEST_DATABASE_USER=root MIGRATION_TEST_DATABASE_PASSWORD=migration-test-only \
  ./gradlew --no-daemon --max-workers=2 test --tests '*CompanyIntegrationTest' --rerun-tasks
