// Week 6, step 1: order creation through admission at a constant arrival rate. Every request has its own
// Idempotency-Key and one of ~100,000 X-Client-Id values, so the per-client limiter is not what is measured.
// Outcomes are counters with tags (status, err); accepted (201) latency is a trend. The err tag is the
// error type in the admission's error body (shed, pool_timeout, order_unavailable, other, read_timeout, ...).
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://admission:8080';
const RATE = Number(__ENV.RATE || 800);
const DURATION = __ENV.DURATION || '30s';
const CENTRES = { Pune: [18.52, 73.86], Mumbai: [19.08, 72.88], Delhi: [28.61, 77.21] };
const CITIES = Object.keys(CENTRES);
const outcome = new Counter('outcome');
const acceptedLatency = new Trend('accepted_latency_ms', true);

export const options = {
  scenarios: {
    create: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION,
              preAllocatedVUs: 300, maxVUs: 3000, exec: 'create' },
  },
  thresholds: {},
};

export function create() {
  const city = CITIES[Math.floor(Math.random() * CITIES.length)];
  const [lat0, lon0] = CENTRES[city];
  const client = `c-${Math.floor(Math.random() * 100000)}`;
  const body = JSON.stringify({
    customerId: client, city, totalPaise: 100 + Math.floor(Math.random() * 5000),
    pickupLat: lat0 + (Math.random() * 2 - 1) * 0.07, pickupLon: lon0 + (Math.random() * 2 - 1) * 0.07,
  });
  const res = http.post(`${BASE}/orders`, body, { headers: {
    'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(), 'X-Client-Id': client } });
  let err = 'none';
  if (res.status >= 400) {
    try { err = res.json('error') || 'no_error_field'; } catch (e) { err = 'unparsed'; }
  }
  outcome.add(1, { status: String(res.status), err });
  if (res.status === 201) {
    acceptedLatency.add(res.timings.duration);
  }
}
