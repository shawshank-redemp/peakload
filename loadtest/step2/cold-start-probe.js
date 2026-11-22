// Week 4 step 5, item 2: creates at CREATE_RATE through admission (limiter and shedding off, as in the bisect),
// counting statuses and the upstream error type from the error body ("error" field, e.g. pool_timeout, other).
// k6 does not export per-tag counts in the summary, so each error type has its own counter.
import http from 'k6/http';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://admission:8080';
const CREATE_RATE = Number(__ENV.CREATE_RATE || 4400);
const DURATION = __ENV.DURATION || '30s';
const CITIES = ['Pune', 'Mumbai', 'Delhi', 'Chennai', 'Kolkata'];

const s201 = new Counter('status_201');
const s429 = new Counter('status_429');
const s503 = new Counter('status_503');
const s504 = new Counter('status_504');
const sOther = new Counter('status_other');
const eCon = new Counter('err_connect_timeout');
const ePool = new Counter('err_pool_timeout');
const eRefused = new Counter('err_connection_refused');
const eRead = new Counter('err_read_timeout');
const eOther = new Counter('err_other');
const eUnknown = new Counter('err_unparsed');

let logged = 0;

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
  const counters = { 201: s201, 429: s429, 503: s503, 504: s504 };
  (counters[res.status] || sOther).add(1);
  if (res.status >= 400) {
    let type = null;
    try { type = res.json('error'); } catch (e) { type = null; }
    const byType = { connect_timeout: eCon, pool_timeout: ePool, connection_refused: eRefused, read_timeout: eRead, other: eOther };
    if (type && byType[type]) byType[type].add(1);
    else eUnknown.add(1);
    if (logged < 3) { logged++; console.log(`error sample status=${res.status} body=${res.body}`); }
  }
}
