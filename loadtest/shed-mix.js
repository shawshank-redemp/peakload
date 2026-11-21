// Mixed load for the Week 3 Step 4 load-shedding measurement. Four phases of PHASE_LEN each.
//
//   CRITICAL  POST /orders            CRIT_RATE req/s in every phase (the class that must keep working)
//   STANDARD  GET /orders/{id}        STD_RATE req/s in every phase (a modest steady rate)
//   LOW       GET .../menu            phase 1: none; phases 2-4: LOW_RATES[0..2] req/s (ramps up)
//
// Every request has its own scenario per phase, so each scenario carries its phase and class as tags, and the
// points file can be split by phase and class afterwards. The rates are test assumptions, not real traffic.
//
//   docker compose --profile loadtest run --rm --no-deps -e BASE_URL=http://admission:8080 \
//     -e CRIT_RATE=800 -e STD_RATE=50 -e LOW_RATES=400,1200,2400 -e PHASE_LEN=30s k6 run /loadtest/shed-mix.js
//
// No thresholds: this test reports and does not pass or fail.
import http from 'k6/http';

const BASE = __ENV.BASE_URL || 'http://admission:8080';
const CRIT_RATE = Number(__ENV.CRIT_RATE || 800);
const STD_RATE = Number(__ENV.STD_RATE || 50);
const LOW_RATES = (__ENV.LOW_RATES || '400,1200,2400').split(',').map(Number);
const PHASE_SECONDS = Number((__ENV.PHASE_LEN || '30s').replace('s', ''));
const MENU_ID = '11111111-1111-4111-8111-111111111111'; // seeded restaurant (V2__seed_sample_menu.sql)
const CITIES = ['Pune', 'Mumbai', 'Delhi', 'Chennai', 'Kolkata'];

function phaseScenario(name, rate, startSeconds, exec, phase, cls) {
  return {
    executor: 'constant-arrival-rate',
    rate: rate,
    timeUnit: '1s',
    duration: `${PHASE_SECONDS}s`,
    startTime: `${startSeconds}s`,
    preAllocatedVUs: Math.max(20, Math.ceil(rate / 2)),
    maxVUs: Math.max(200, rate * 2),
    exec: exec,
    tags: { phase: phase, class: cls, scenario: name },
  };
}

const scenarios = {};
const phases = ['p1', 'p2', 'p3', 'p4'];
// ONLY_FIRST=1 runs phase 1 alone: the warm-up, whose numbers are discarded.
const ONLY_FIRST = __ENV.ONLY_FIRST === '1';
phases.forEach((phase, i) => {
  if (ONLY_FIRST && i > 0) {
    return;
  }
  const start = i * PHASE_SECONDS;
  scenarios[`critical_${phase}`] = phaseScenario(`critical_${phase}`, CRIT_RATE, start, 'critical', phase, 'CRITICAL');
  if (STD_RATE > 0) {
    scenarios[`standard_${phase}`] = phaseScenario(`standard_${phase}`, STD_RATE, start, 'standard', phase, 'STANDARD');
  }
  // Phase 1 is the calm baseline: no LOW. Phases 2-4 use LOW_RATES[0..2].
  if (i > 0 && LOW_RATES[i - 1] > 0) {
    scenarios[`low_${phase}`] = phaseScenario(`low_${phase}`, LOW_RATES[i - 1], start, 'low', phase, 'LOW');
  }
});

export const options = {
  summaryTrendStats: ['med', 'p(95)', 'p(99)'],
  scenarios: scenarios,
};

// One order for the STANDARD reads, created in setup so it exists before the load starts.
export function setup() {
  const res = http.post(`${BASE}/orders`, orderBody(), {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(), 'X-Client-Id': 'setup' },
  });
  if (res.status !== 201) {
    throw new Error(`setup could not create an order: HTTP ${res.status} ${res.body}`);
  }
  return { orderId: res.json('orderId') };
}

function orderBody() {
  return JSON.stringify({
    customerId: `c-${Math.floor(Math.random() * 100000)}`,
    city: CITIES[Math.floor(Math.random() * CITIES.length)],
    totalPaise: 100 + Math.floor(Math.random() * 5000),
    pickupLat: 18.45 + Math.random() * 0.15,
    pickupLon: 73.80 + Math.random() * 0.15,
  });
}

export function critical() {
  http.post(`${BASE}/orders`, orderBody(), {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(), 'X-Client-Id': 'crit' },
  });
}

export function standard(data) {
  http.get(`${BASE}/orders/${data.orderId}`, { headers: { 'X-Client-Id': 'std' } });
}

export function low() {
  http.get(`${BASE}/browse/restaurants/${MENU_ID}/menu`, { headers: { 'X-Client-Id': 'low' } });
}
