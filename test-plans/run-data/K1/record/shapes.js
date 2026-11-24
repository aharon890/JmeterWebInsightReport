// K1: every shape a k6 run can write, against local stub servers (record.sh). Recorded with perf-hub-k6:2.4.0.
// Shapes: a request outside any group, a group with a nested group, a 500, a refused connection (status 0),
// an http.batch, a redirect (two hops), a second pass of the same group, an unnamed request, setup and teardown,
// checks that pass and fail, and thresholds that pass and fail.
import http from 'k6/http';
import { check, group, sleep } from 'k6';

export const options = {
  scenarios: {
    flow: { executor: 'constant-arrival-rate', rate: 2, timeUnit: '1s', duration: '6s',
            preAllocatedVUs: 3, maxVUs: 4, gracefulStop: '0s' },
  },
  thresholds: {
    'http_req_duration': ['p(95)<5000'],
    'http_req_duration{name:Login}': ['p(95)<5000'],
    'http_req_failed': ['rate<0.01'],
  },
};

const B = 'http://127.0.0.1';

export function setup() { http.get(`${B}:8080/setup`, { tags: { name: 'Setup Read' } }); }

export default function () {
  http.get(`${B}:8080/outside`, { tags: { name: 'Outside Group' } });
  group('Complete Checkout Flow', () => {
    const login = http.get(`${B}:8080/login`, { tags: { name: 'Login' } });
    check(login, { 'login is 200': (r) => r.status === 200 });
    sleep(0.2);
    group('Inner Payment', () => {
      http.get(`${B}:8080/pay`, { tags: { name: 'Pay' } });
      sleep(0.1);
    });
    const fail = http.get(`${B}:8081/fail`, { tags: { name: 'Server Error' } });
    check(fail, { 'server error is 200': (r) => r.status === 200 });
    http.get(`${B}:8099/refused`, { tags: { name: 'Refused' } });
    http.batch([['GET', `${B}:8080/b1`, null, { tags: { name: 'Batch A' } }],
                ['GET', `${B}:8080/b2`, null, { tags: { name: 'Batch B' } }]]);
    http.get(`${B}:8082/redirect`, { tags: { name: 'Redirected' } });
    sleep(0.3);
  });
  group('Complete Checkout Flow', () => { http.get(`${B}:8080/again`, { tags: { name: 'Second Pass' } }); });
  http.get(`${B}:8080/unnamed?token=secret123`);
}

export function teardown() { http.get(`${B}:8080/teardown`, { tags: { name: 'Teardown Clean' } }); }

export function handleSummary(data) {
  return { '/w/summary.json': JSON.stringify(data, null, 1) };
}
