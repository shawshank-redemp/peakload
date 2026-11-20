// NYE-shaped curve: quiet, lunch rush, then a 10x spike. Shape only for now;
// from week 3 it will target real endpoints (browse vs checkout) with a traffic mix.
//   docker run --rm -i --network host -e BASE_RATE=100 grafana/k6 run - < loadtest/nye-curve.js
import http from 'k6/http';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const R = Number(__ENV.BASE_RATE || 100);

export const options = {
  scenarios: {
    nye: {
      executor: 'ramping-arrival-rate',
      startRate: R,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 2000,
      stages: [
        { target: R,      duration: '1m'  },   // normal
        { target: R * 4,  duration: '1m'  },   // lunch rush
        { target: R * 4,  duration: '1m'  },
        { target: R * 10, duration: '30s' },   // NYE spike
        { target: R * 10, duration: '2m'  },
        { target: R,      duration: '1m'  },   // recovery
      ],
    },
  },
};

export default function () {
  http.get(`${BASE}/api/ping`);
}
