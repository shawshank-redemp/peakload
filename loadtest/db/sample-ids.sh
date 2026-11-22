#!/usr/bin/env bash
# Writes 20,000 random order ids from the seeded data to loadtest/db/order-ids.txt, for the GET read mix.
set -euo pipefail
cd "$(dirname "$0")/../.."
docker compose exec -T postgres psql -U peakload -d peakload -At -c \
  "SELECT id FROM orders ORDER BY random() LIMIT 20000" > loadtest/db/order-ids.txt
wc -l < loadtest/db/order-ids.txt
