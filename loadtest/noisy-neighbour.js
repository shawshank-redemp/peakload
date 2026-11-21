// Noisy neighbour: five normal clients at a modest steady rate, and one abusive client far above its limit.
// Same duration for everyone. Each client has its own X-Client-Id, so each has its own bucket.
//
//   docker compose --profile loadtest run --rm --no-deps -e ABUSE_RATE=1200 -e NORMAL_RATE=20 -e DURATION=30s \
//     -e BASE_URL=http://admission:8080 k6 run /loadtest/noisy-neighbour.js
//
// No thresholds: this test reports, it does not pass or fail. Results are split by group ("normal", "abusive").
import http from 'k6/http';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://admission:8080';
const NORMAL_RATE = Number(__ENV.NORMAL_RATE || 20);
const ABUSE_RATE = Number(__ENV.ABUSE_RATE || 1200);
const DURATION = __ENV.DURATION || '30s';
const NORMAL_CLIENTS = 5;
const CITIES = ['Pune', 'Mumbai', 'Delhi', 'Chennai', 'Kolkata'];

const allowed = new Counter('allowed_201');
const limited = new Counter('limited_429');
const failed = new Counter('failed_other');

function scenario(rate, group, exec) {
  return {
    executor: 'constant-arrival-rate',
    rate: rate,
    timeUnit: '1s',
    duration: DURATION,
    preAllocatedVUs: Math.max(20, Math.ceil(rate / 4)),
    maxVUs: Math.max(100, rate),
    exec: exec,
    tags: { group: group },
  };
}

// One scenario per client, so each normal client is a separate caller with its own X-Client-Id and bucket.
const scenarios = {};
for (let i = 1; i <= NORMAL_CLIENTS; i++) {
  scenarios[`normal_${i}`] = scenario(NORMAL_RATE, 'normal', `normal${i}`);
}
scenarios.abuser = scenario(ABUSE_RATE, 'abusive', 'abusive');

export const options = {
  summaryTrendStats: ['med', 'p(95)', 'p(99)'],
  scenarios: scenarios,
};

function order(client, group) {
  const body = JSON.stringify({
    customerId: `c-${Math.floor(Math.random() * 100000)}`,
    city: CITIES[Math.floor(Math.random() * CITIES.length)],
    totalPaise: 100 + Math.floor(Math.random() * 5000),
    pickupLat: 18.45 + Math.random() * 0.15,
    pickupLon: 73.80 + Math.random() * 0.15,
  });
  const res = http.post(`${BASE}/orders`, body, {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID(),
      'X-Client-Id': client,
    },
    tags: { group: group },
  });
  if (res.status === 201) {
    allowed.add(1, { group: group });
  } else if (res.status === 429) {
    limited.add(1, { group: group });
  } else {
    failed.add(1, { group: group });
  }
}

export function normal1() { order('normal-1', 'normal'); }
export function normal2() { order('normal-2', 'normal'); }
export function normal3() { order('normal-3', 'normal'); }
export function normal4() { order('normal-4', 'normal'); }
export function normal5() { order('normal-5', 'normal'); }
export function abusive() { order('abuser', 'abusive'); }
