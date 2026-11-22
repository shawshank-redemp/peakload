#!/usr/bin/env bash
# Week 6, step 2: an HTTP GET from inside a service container (the compose network), for scripts that used to reach the
# order, dispatch and tracking ports on the host. Prints the response body.
#   bash loadtest/http-in-network.sh <service> <port> <path>
#   e.g. bash loadtest/http-in-network.sh order 8081 /actuator/prometheus
set -uo pipefail
SVC=${1:?service}; PORT=${2:?port}; URL_PATH=${3:?path}
cd "$(dirname "$0")/.."
docker compose exec -T "$SVC" bash -c "exec 3<>/dev/tcp/127.0.0.1/$PORT && printf 'GET $URL_PATH HTTP/1.0\r\nHost: x\r\n\r\n' >&3 && cat <&3" 2>/dev/null \
  | awk 'body {print} /^\r?$/ {body=1}'
