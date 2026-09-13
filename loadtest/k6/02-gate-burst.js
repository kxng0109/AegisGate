/*
 * 02-gate-burst: 3-minute MEASUREMENT burst at 500 rps (gate-only probe, same
 * as 01-gate-smoke: valid key + unknown model -> local 404, no upstream).
 * This is a measurement run, not a gate: thresholds are informational.
 * Results feed the P2 carrier A/B (parallelism 4 vs 8) and P3 bottleneck order.
 * Run: k6 run loadtest/k6/02-gate-burst.js
 * Env:  BASE_URL (default http://localhost:8080)
 *       LOAD_KEY (default gw-0123456789abcdef0123456789abcdef — compose load-test key)
 */
import http from 'k6/http';
import {check} from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const KEY = __ENV.LOAD_KEY || 'gw-0123456789abcdef0123456789abcdef';

export const options = {
    scenarios: {
        burst: {
            executor: 'constant-arrival-rate',
            rate: 500,
            timeUnit: '1s',
            duration: '3m',
            preAllocatedVUs: 60,
            maxVUs: 150,
        },
    },
    thresholds: {
        checks: ['rate>0.95'],
    },
    tags: {flow: 'gate-burst'},
};

const BODY = JSON.stringify({model: 'no-such-model-xyz', messages: [{role: 'user', content: 'Hi'}]});

export default function () {
    const res = http.post(BASE + '/v1/chat/completions', BODY, {
        headers: {'Content-Type': 'application/json', Authorization: 'Bearer ' + KEY},
    });
    check(res, {
        'gate passed, rejected locally': (r) => r.status === 404 && r.body.includes('unknown model'),
        'gate verdict fast': (r) => r.timings.duration < 2000,
    });
}
