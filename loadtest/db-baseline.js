// Week 4 database baseline workload. Same create load as Week 1 (loadtest/order-create.js: open loop, fresh
// Idempotency-Key per request, X-Client-Id loadtest, direct to the order service), plus a small GET read mix on
// orders that already exist in the big dataset.
//
//   WARMUP=1 : 30 s of creates at 20 req/s, no reads. Discarded (same warm-up as Week 1).
//   default  : 30 s of creates at CREATE_RATE (4400 req/s, the highest rate Week 1 kept clean) and 30 s of reads
//              at READ_RATE (50 req/s) at the same time.
//
// Thresholds are the same as Week 1 (loadtest/baseline.js) and are reported, not used to stop the run.
// Read ids come from loadtest/db/order-ids.txt, written by loadtest/db/sample-ids.sh from the seeded orders.
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';

const BASE = __ENV.BASE_URL || 'http://order:8081';
const WARMUP = __ENV.WARMUP === '1';
const CREATE_RATE = Number(__ENV.CREATE_RATE || 4400);
const READ_RATE = Number(__ENV.READ_RATE || 50);
const DURATION = __ENV.DURATION || '30s';
const CITIES = ['Pune', 'Mumbai', 'Delhi', 'Chennai', 'Kolkata'];
const ids = new SharedArray('order-ids', () => open('./db/order-ids.txt').split('\n').filter((s) => s.length > 0));

export const options = {
  summaryTrendStats: ['med', 'p(95)', 'p(99)'],
  scenarios: WARMUP
    ? {
        create: {
          executor: 'constant-arrival-rate',
          rate: 20,
          timeUnit: '1s',
          duration: '30s',
          preAllocatedVUs: 50,
          maxVUs: 200,
          exec: 'create',
        },
      }
    : {
        create: {
          executor: 'constant-arrival-rate',
          rate: CREATE_RATE,
          timeUnit: '1s',
          duration: DURATION,
          preAllocatedVUs: 200,
          maxVUs: 2000,
          exec: 'create',
          tags: { workload: 'create' },
        },
        read: {
          executor: 'constant-arrival-rate',
          rate: READ_RATE,
          timeUnit: '1s',
          duration: DURATION,
          preAllocatedVUs: 20,
          maxVUs: 200,
          exec: 'read',
          tags: { workload: 'read' },
        },
      },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(99)<200'],
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
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID(),
      'X-Client-Id': 'loadtest',
    },
  });
  check(res, { 'status is 201': (r) => r.status === 201 });
}

export function read() {
  const id = ids[Math.floor(Math.random() * ids.length)];
  const res = http.get(`${BASE}/orders/${id}`, { tags: { workload: 'read' } });
  check(res, { 'read status is 200': (r) => r.status === 200 });
}
