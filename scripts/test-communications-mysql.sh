#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
container="finstage-communications-mysql-${RANDOM}"
cleanup() { docker rm -f "$container" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker run --detach --name "$container" --publish 127.0.0.1::3306 \
  --env MYSQL_ROOT_PASSWORD=migration-test-only \
  --env MYSQL_DATABASE=finstage_analysis_test mysql:8.0 >/dev/null
ready=0
for attempt in $(seq 1 90); do
  if docker exec "$container" mysql --protocol=TCP -h127.0.0.1 -uroot -pmigration-test-only -Nse 'SELECT 1' >/dev/null 2>&1; then ready=1; break; fi
  sleep 1
done
if [ "$ready" -ne 1 ]; then echo 'MySQL did not become ready' >&2; exit 1; fi
# Isolated schema generated from source; never applies to an existing database.
docker exec -i "$container" mysql -uroot -pmigration-test-only finstage_analysis_test < src/main/resources/db/reference-schema.mysql.sql
port="$(docker port "$container" 3306/tcp | awk -F: '{print $NF}')"
COMMUNICATIONS_MYSQL_URL="jdbc:mysql://127.0.0.1:${port}/finstage_analysis_test?allowPublicKeyRetrieval=true&useSSL=false" \
COMMUNICATIONS_MYSQL_USER=root COMMUNICATIONS_MYSQL_PASSWORD=migration-test-only \
  ./gradlew --no-daemon --max-workers=2 test --tests '*CommunicationsIntegrationTest' --rerun-tasks
