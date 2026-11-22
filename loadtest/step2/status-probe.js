// Week 4 step 2 diagnostic: creates at CREATE_RATE, counting every response by status code (k6 does not
// break status codes down by itself: explicit counters per status, default k6 failure rule, no expected statuses). Same body and headers as loadtest/db-baseline.js.
import http from 'k6/http';
import { Counter } from 'k6/metrics';

const c201 = new Counter('status_201');
const c429 = new Counter('status_429');
const c503 = new Counter('status_503');
const c504 = new Counter('status_504');
const cOther = new Counter('status_other');

const BASE = __ENV.BASE_URL || 'http://admission:8080';
const CREATE_RATE = Number(__ENV.CREATE_RATE || 4400);
const DURATION = __ENV.DURATION || '30s';
const CITIES = ['Pune', 'Mumbai', 'Delhi', 'Chennai', 'Kolkata'];

export const options = {
  scenarios: {
    create: {
      executor: 'constant-arrival-rate', rate: CREATE_RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 200, maxVUs: 2000, exec: 'create',
    },
  },
};

export function create() {
  const body = JSON.stringify({
    customerId: `c-${Math.floor(Math.random() * 100000)}`,
    city: CITIES[Math.floor(Math.random() * CITIES.length)],
    totalPaise: 100 + Math.floor(Math.random() * 5000),
    pickupLat: 18.45 + Math.random() * 0.15,
    pickupLon: 73.80 + Math.random() * 0.15,
  });
  const res = http.post(`${BASE}/orders`, body, {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(), 'X-Client-Id': 'loadtest' },
  });
  const counter = { 201: c201, 429: c429, 503: c503, 504: c504 }[res.status] || cOther;
  counter.add(1);
}
