// Week 5 step 1, measurement E: a mix of creates, order reads and menu reads through admission, at a constant rate.
// Every request gets its own X-Client-Id (from a pool of 50,000), so the per-client rate limiter is not what is
// being measured. Each outcome is a counter with tags (phase, status, err) and each failed request has a latency
// trend, so the per-phase numbers come from the k6 JSON points, not from the summary.
//   T0 (epoch ms, set by the runner) is the start of the run. Phases: before 0-30 s, during 30-60 s, after 60-90 s.
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://admission:8080';
const RATE = Number(__ENV.RATE || 800);
const DURATION = __ENV.DURATION || '90s';
const T0 = Number(__ENV.T0 || Date.now());
const MENU = '/browse/restaurants/11111111-1111-4111-8111-111111111111/menu';
const CITIES = ['Pune', 'Mumbai', 'Delhi'];

const outcome = new Counter('outcome');
const failLatency = new Trend('fail_latency_ms', true);
const okLatency = new Trend('ok_latency_ms', true);

export const options = {
  scenarios: {
    mix: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION,
           preAllocatedVUs: 300, maxVUs: 3000, exec: 'mix' },
  },
  // Thresholds are reported, not used to stop the run.
  thresholds: {},
};

let ids = []; // order ids this VU created, for later reads

function phase() {
  const s = (Date.now() - T0) / 1000;
  return s < 30 ? 'before' : s < 60 ? 'during' : 'after';
}

function record(kind, res, ph) {
  let err = 'none';
  if (res.status >= 400) {
    try { err = res.json('error') || 'no_error_field'; } catch (e) { err = 'unparsed'; }
  }
  outcome.add(1, { kind, phase: ph, status: String(res.status), err });
  if (res.status >= 400) {
    failLatency.add(res.timings.duration, { kind, phase: ph, status: String(res.status) });
  } else {
    okLatency.add(res.timings.duration, { kind, phase: ph });
  }
}

export function mix() {
  const ph = phase();
  const client = `c-${Math.floor(Math.random() * 50000)}`;
  const roll = Math.random();
  if (roll < 0.5 || ids.length === 0) {
    const body = JSON.stringify({
      customerId: client, city: CITIES[Math.floor(Math.random() * CITIES.length)],
      totalPaise: 100 + Math.floor(Math.random() * 5000),
      pickupLat: 18.45 + Math.random() * 0.15, pickupLon: 73.80 + Math.random() * 0.15,
    });
    const res = http.post(`${BASE}/orders`, body, { headers: {
      'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(), 'X-Client-Id': client } });
    record('create', res, ph);
    if (res.status === 201) {
      try { ids.push(res.json('orderId')); } catch (e) { /* not an order id reply: nothing to read later */ }
      if (ids.length > 200) ids.shift();
    }
  } else if (roll < 0.8) {
    const id = ids[Math.floor(Math.random() * ids.length)];
    record('read', http.get(`${BASE}/orders/${id}`, { headers: { 'X-Client-Id': client } }), ph);
  } else {
    record('menu', http.get(`${BASE}${MENU}`, { headers: { 'X-Client-Id': client } }), ph);
  }
}
