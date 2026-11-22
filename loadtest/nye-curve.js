// New Year's Eve curve (Week 3 Step 5). Open loop: every stage is a constant-arrival-rate scenario, so k6 starts
// requests on schedule whether or not earlier ones have finished. A slow service shows up as latency and
// dropped iterations, not as fewer requests.
//
// Shape, 300 s in total (MODE=curve):
//   calm      0-60 s     rate = base                      (f = 0)
//   ramp      60-120 s   four 15 s steps at f = 0.25, 0.5, 0.75, 1.0
//   peak      120-150 s  f = 1.0 (the sharp peak)
//   decline   150-210 s  three 20 s steps at f = 0.75, 0.5, 0.25
//   tail      210-300 s  f = 0 (calm again; the outbox drain is measured here)
// Each class's rate in a stage = base + f * (peak - base). MODE=warmup runs only the calm stage for 30 s; its
// numbers are discarded.
//
// Mix and rates are TEST ASSUMPTIONS, not real traffic:
//   CRITICAL  POST /orders            base 200/s, peak 1000/s   fresh Idempotency-Key, 300 client ids
//   STANDARD  GET /orders/{id}        base 20/s,  peak 100/s    reads a seeded order; 20% also advance its status
//                                                              (CREATED -> CONFIRMED -> ... -> DELIVERED)
//   LOW       GET .../menu            base 400/s, peak 2400/s   300 client ids
// Peak reasoning: Step 4 showed CRITICAL at 800/s alone passes, and that LOW at 2400/s with CRITICAL at 800/s
// pushes the system into the failure region. The peak is just above the CRITICAL level that passed, and at the LOW
// level that hurt. Week 1's knee for POST /orders alone was between 5200 and 5600/s, so the peak is well below it.
//
//   docker compose --profile loadtest run --rm --no-deps -e BASE_URL=http://admission:8080 -e MODE=curve \
//     k6 run /loadtest/nye-curve.js
//
// No thresholds: this test reports and does not pass or fail.
import http from 'k6/http';

const BASE = __ENV.BASE_URL || 'http://admission:8080';
const MODE = __ENV.MODE || 'curve';
const SEEDS = Number(__ENV.SEEDS || 400);
const CLIENTS = 300;
const CITIES = ['Pune', 'Mumbai', 'Delhi', 'Chennai', 'Kolkata'];
const MENU_ID = '11111111-1111-4111-8111-111111111111'; // seeded restaurant (V2__seed_sample_menu.sql)
const BASE_RATE = { CRITICAL: 200, STANDARD: 20, LOW: 400 };
// Week 6, step 2: PEAK_CRITICAL scales the POST /orders peak. The default (1000) is the Week 3 value.
const PEAK_RATE = { CRITICAL: Number(__ENV.PEAK_CRITICAL || 1000), STANDARD: 100, LOW: 2400 };
const NEXT_STATUS = {
  CREATED: 'CONFIRMED', CONFIRMED: 'PREPARING', PREPARING: 'READY',
  READY: 'PICKED_UP', PICKED_UP: 'DELIVERED',
};

// [name, start s, duration s, f]
const STAGES = MODE === 'warmup'
  ? [['warmup', 0, 30, 0]]
  : [
      ['calm', 0, 60, 0],
      ['ramp1', 60, 15, 0.25], ['ramp2', 75, 15, 0.5], ['ramp3', 90, 15, 0.75], ['ramp4', 105, 15, 1.0],
      ['peak', 120, 30, 1.0],
      ['decline1', 150, 20, 0.75], ['decline2', 170, 20, 0.5], ['decline3', 190, 20, 0.25],
      ['tail', 210, 90, 0],
    ];

function stageRate(cls, f) {
  return Math.round(BASE_RATE[cls] + f * (PEAK_RATE[cls] - BASE_RATE[cls]));
}

const scenarios = {};
for (const [stage, start, duration, f] of STAGES) {
  for (const cls of ['CRITICAL', 'STANDARD', 'LOW']) {
    const rate = stageRate(cls, f);
    if (rate <= 0) {
      continue;
    }
    scenarios[`${stage}_${cls}`] = {
      executor: 'constant-arrival-rate',
      rate: rate,
      timeUnit: '1s',
      duration: `${duration}s`,
      startTime: `${start}s`,
      preAllocatedVUs: Math.ceil(rate / 2) + 10,
      maxVUs: rate * 2 + 50,
      exec: cls === 'CRITICAL' ? 'critical' : cls === 'STANDARD' ? 'standard' : 'low',
      tags: { phase: stage, class: cls },
    };
  }
}

export const options = {
  summaryTrendStats: ['med', 'p(95)', 'p(99)'],
  scenarios: scenarios,
};

function orderBody() {
  return JSON.stringify({
    customerId: `c-${Math.floor(Math.random() * 100000)}`,
    city: CITIES[Math.floor(Math.random() * CITIES.length)],
    totalPaise: 100 + Math.floor(Math.random() * 5000),
    pickupLat: 18.45 + Math.random() * 0.15,
    pickupLon: 73.80 + Math.random() * 0.15,
  });
}

function clientId(prefix) {
  return `${prefix}-${Math.floor(Math.random() * CLIENTS)}`;
}

// Seeded orders for STANDARD, created before the run starts. They are part of the orders table.
export function setup() {
  const ids = [];
  for (let i = 0; i < SEEDS; i++) {
    const res = http.post(`${BASE}/orders`, orderBody(), {
      // One client id per seed: a single client would hit its normal rate-limit burst (200) during setup.
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(), 'X-Client-Id': `seed-${i}` },
    });
    if (res.status !== 201) {
      throw new Error(`seeding failed at ${i}: HTTP ${res.status} ${res.body}`);
    }
    ids.push(res.json('orderId'));
  }
  return { ids: ids };
}

export function critical() {
  http.post(`${BASE}/orders`, orderBody(), {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID(),
      'X-Client-Id': clientId('crit'),
    },
  });
}

export function standard(data) {
  const id = data.ids[Math.floor(Math.random() * data.ids.length)];
  const headers = { 'X-Client-Id': clientId('std') };
  const read = http.get(`${BASE}/orders/${id}`, { headers: headers });
  if (read.status !== 200 || Math.random() >= 0.2) {
    return;
  }
  const status = read.json('status');
  const next = NEXT_STATUS[status];
  if (!next) {
    return;
  }
  http.patch(`${BASE}/orders/${id}/status`, JSON.stringify({ status: next }), {
    headers: {
      'Content-Type': 'application/json',
      'If-Match': String(read.json('version')),
      'X-Client-Id': headers['X-Client-Id'],
    },
  });
}

export function low() {
  http.get(`${BASE}/browse/restaurants/${MENU_ID}/menu`, { headers: { 'X-Client-Id': clientId('low') } });
}
