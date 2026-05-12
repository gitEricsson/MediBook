import http from 'k6/http';
import { check, fail, sleep } from 'k6';

const GENERATED_TEST_ID = `local-${new Date().toISOString().replace(/[:.]/g, '-')}`;
const BASE_URL = __ENV.K6_BASE_URL || 'http://app:8080';
const USERNAME = __ENV.K6_USERNAME || 'patient.james@medibook.local';
const PASSWORD = __ENV.K6_PASSWORD || 'Password123!';

const WARMUP_DURATION = __ENV.K6_WARMUP_DURATION || '30s';
const RAMP_UP_DURATION = __ENV.K6_RAMP_UP_DURATION || '1m';
const STEADY_STATE_DURATION = __ENV.K6_STEADY_STATE_DURATION || '3m';
const RAMP_DOWN_DURATION = __ENV.K6_RAMP_DOWN_DURATION || '30s';

const START_RATE = Number(__ENV.K6_START_RATE || 5);
const TARGET_RATE = Number(__ENV.K6_TARGET_RATE || 20);
const PRE_ALLOCATED_VUS = Number(__ENV.K6_PRE_ALLOCATED_VUS || 30);
const MAX_VUS = Number(__ENV.K6_MAX_VUS || 120);

const GLOBAL_P99_THRESHOLD_MS = Number(__ENV.K6_P99_THRESHOLD_MS || 750);
const SEARCH_P99_THRESHOLD_MS = Number(__ENV.K6_SEARCH_P99_THRESHOLD_MS || 1000);

const DOCTOR_QUERIES = ['cardio', 'derm', 'pediatrics', 'neurology', 'family'];

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'p(99.9)'],
  tags: {
    service: 'medibook',
    suite: 'read-path',
    testid: __ENV.K6_TESTID || GENERATED_TEST_ID,
  },
  scenarios: {
    warmup: {
      executor: 'constant-vus',
      exec: 'warmup',
      vus: 1,
      duration: WARMUP_DURATION,
      gracefulStop: '0s',
    },
    steady_state: {
      executor: 'ramping-arrival-rate',
      exec: 'steadyState',
      startTime: WARMUP_DURATION,
      timeUnit: '1s',
      startRate: START_RATE,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
      stages: [
        { target: TARGET_RATE, duration: RAMP_UP_DURATION },
        { target: TARGET_RATE, duration: STEADY_STATE_DURATION },
        { target: 0, duration: RAMP_DOWN_DURATION },
      ],
      gracefulStop: '30s',
    },
  },
  thresholds: {
    'checks{scenario:steady_state}': ['rate>0.99'],
    'http_req_failed{scenario:steady_state}': ['rate<0.01'],
    'http_req_duration{scenario:steady_state}': [`p(99)<${GLOBAL_P99_THRESHOLD_MS}`],
    'http_req_duration{name:GET /api/v1/doctors/search,scenario:steady_state}': [
      `p(99)<${SEARCH_P99_THRESHOLD_MS}`,
    ],
    'dropped_iterations{scenario:steady_state}': ['count==0'],
  },
};

export function setup() {
  const loginResponse = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({
      email: USERNAME,
      password: PASSWORD,
    }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'POST /api/v1/auth/login' },
    }
  );

  const loginOk = check(loginResponse, {
    'login succeeded': (response) => response.status === 200,
  });

  if (!loginOk) {
    fail(
      `Unable to authenticate k6 test user (${USERNAME}). ` +
        `Expected a 200 from /api/v1/auth/login but received ${loginResponse.status}.`
    );
  }

  const body = loginResponse.json();
  const token = body && body.data ? body.data.accessToken : null;

  if (!token) {
    fail('Login response did not include data.accessToken, so authenticated load tests cannot continue.');
  }

  return { token };
}

export function warmup(data) {
  runJourney(data.token);
}

export function steadyState(data) {
  runJourney(data.token);
}

function runJourney(token) {
  const authHeaders = {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
  };

  const query = DOCTOR_QUERIES[Math.floor(Math.random() * DOCTOR_QUERIES.length)];

  const healthResponse = http.get(`${BASE_URL}/health`, {
    tags: { name: 'GET /health' },
  });
  check(healthResponse, {
    'health is 200': (response) => response.status === 200,
  });

  const metadataResponse = http.get(`${BASE_URL}/api/v1/metadata/specialisations`, {
    tags: { name: 'GET /api/v1/metadata/specialisations' },
  });
  check(metadataResponse, {
    'specialisations is 200': (response) => response.status === 200,
  });

  const meResponse = http.get(`${BASE_URL}/api/v1/auth/me`, {
    headers: authHeaders,
    tags: { name: 'GET /api/v1/auth/me' },
  });
  check(meResponse, {
    'me is 200': (response) => response.status === 200,
  });

  const doctorSearchResponse = http.get(
    `${BASE_URL}/api/v1/doctors/search?page=0&size=5&q=${encodeURIComponent(query)}`,
    {
      headers: authHeaders,
      tags: { name: 'GET /api/v1/doctors/search' },
    }
  );
  check(doctorSearchResponse, {
    'doctor search is 200': (response) => response.status === 200,
  });

  sleep(Math.random());
}

export function handleSummary(data) {
  const duration = data.metrics.http_req_duration.values;
  const failed = data.metrics.http_req_failed.values;
  const dropped = data.metrics.dropped_iterations ? data.metrics.dropped_iterations.values : null;
  const requests = data.metrics.http_reqs.values;

  const report = [
    `testid=${options.tags.testid}`,
    `base_url=${BASE_URL}`,
    `requests=${requests.count}`,
    `http_req_duration_p99_ms=${duration['p(99)']}`,
    `http_req_duration_p999_ms=${duration['p(99.9)']}`,
    `http_req_failed_rate=${failed.rate}`,
    `dropped_iterations=${dropped ? dropped.count : 0}`,
  ].join('\n');

  return {
    'results/summary.json': JSON.stringify(data, null, 2),
    'results/summary.txt': report,
    stdout: `${report}\n`,
  };
}
