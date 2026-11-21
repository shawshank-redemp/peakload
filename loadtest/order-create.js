// Order creation under constant arrival rate (open loop: k6 starts requests on schedule, whether or
// not earlier ones have finished, so a slow service shows up as latency and dropped iterations).
//
//   docker compose --profile loadtest run --rm --no-deps -e RATE=200 -e DURATION=30s \
//     -e BASE_URL=http://order:8081 k6 run /loadtest/order-create.js
//
// Each request uses a fresh Idempotency-Key, so every request creates one order (no replays).
// Thresholds are the same as loadtest/baseline.js and must not be changed.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://order:8081';
const CLIENT_ID = __ENV.CLIENT_ID || 'loadtest';
const CITIES = ['Pune', 'Mumbai', 'Delhi', 'Chennai', 'Kolkata'];

export const options = {
  // Also export p(99) in the JSON summary (the threshold already needs it).
  summaryTrendStats: ['med', 'p(95)', 'p(99)'],
  scenarios: {
    create_orders: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 50),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      // Sized so k6 is not the limit: about one VU per in-flight request, with headroom.
      preAllocatedVUs: Number(__ENV.PRE_VUS || 200),
      maxVUs: Number(__ENV.MAX_VUS || 2000),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(99)<200'],
  },
};

export default function () {
  const body = JSON.stringify({
    customerId: `c-${Math.floor(Math.random() * 100000)}`,
    city: CITIES[Math.floor(Math.random() * CITIES.length)],
    totalPaise: 100 + Math.floor(Math.random() * 5000),
  });
  const res = http.post(`${BASE}/orders`, body, {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID(),
      'X-Client-Id': CLIENT_ID,
    },
  });
  check(res, { 'status is 201': (r) => r.status === 201 });
}
