// Baseline: constant request rate against the admission service.
//   docker run --rm -i --network host -e RATE=200 grafana/k6 run - < loadtest/baseline.js
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    baseline: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 200),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: 50,
      maxVUs: 500,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(99)<200'],
  },
};

export default function () {
  const res = http.get(`${BASE}/api/ping`);
  check(res, { 'status is 200': (r) => r.status === 200 });
}
