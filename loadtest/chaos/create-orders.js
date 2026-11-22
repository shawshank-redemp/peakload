// Week 5, step 2/3: order creation directly against the order service at a constant rate. Each order's city is
// one of the three seeded cities, and its pickup is inside that city's rider box (the same box as seed-riders.sh),
// so the dispatch search has riders to find. Every request has its own Idempotency-Key.
import http from 'k6/http';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://order:8081';
const RATE = Number(__ENV.RATE || 20);
const DURATION = __ENV.DURATION || '60s';
const CENTRES = {
  Pune: [18.52, 73.86],
  Mumbai: [19.08, 72.88],
  Delhi: [28.61, 77.21],
};
const CITIES = Object.keys(CENTRES);
const created = new Counter('orders_created');

export const options = {
  scenarios: {
    create: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION,
              preAllocatedVUs: 50, maxVUs: 500, exec: 'create' },
  },
};

export function create() {
  const city = CITIES[Math.floor(Math.random() * CITIES.length)];
  const [lat0, lon0] = CENTRES[city];
  const body = JSON.stringify({
    customerId: `c-${Math.floor(Math.random() * 100000)}`,
    city,
    totalPaise: 100 + Math.floor(Math.random() * 5000),
    pickupLat: lat0 + (Math.random() * 2 - 1) * 0.07,
    pickupLon: lon0 + (Math.random() * 2 - 1) * 0.07,
  });
  const res = http.post(`${BASE}/orders`, body, {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(), 'X-Client-Id': 'chaos' },
  });
  if (res.status === 201) {
    created.add(1);
  }
}
