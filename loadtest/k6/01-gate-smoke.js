/*
 * 01-gate-smoke: 60-second GATE-ONLY tripwire. Valid key + unknown model is
 * rejected locally (404) after passing auth + rate Lua + budget Lua, with no
 * upstream call — so duration measures the gateway, not providers. Upstream
 * health (keys present or not) cannot fail this script by design.
 * Run: k6 run loadtest/k6/01-gate-smoke.js
 * Env:  BASE_URL (default http://localhost:8080)
 *       LOAD_KEY (default gw-0123456789abcdef0123456789abcdef — compose load-test key)
 */
import http from 'k6/http';
import {check} from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const KEY = __ENV.LOAD_KEY || 'gw-0123456789abcdef0123456789abcdef';

export const options = {
    scenarios: {
        gate: {
            executor: 'constant-arrival-rate',
            rate: 100,
            timeUnit: '1s',
            duration: '60s',
            preAllocatedVUs: 20,
            maxVUs: 50,
        },
    },
    thresholds: {
        // NOTE: no http_req_failed gate — without provider keys every authed
        // probe ends non-2xx upstream (502/503 or provider-mapped 401). `checks`
        // is the real gate: it asserts the GATEWAY's own verdict, not upstream's.
        checks: ['rate>0.99'],
        http_req_duration: ['p(95)<2000'],
    },
    tags: {flow: 'gate-smoke'},
};

const BODY = JSON.stringify({model: 'no-such-model-xyz', messages: [{role: 'user', content: 'Hi'}]});

export default function () {
    const res = http.post(BASE + '/v1/chat/completions', BODY, {
        headers: {'Content-Type': 'application/json', Authorization: 'Bearer ' + KEY},
    });
    check(res, {
        // 404 unknown-model = passed auth + rate + budget, rejected locally
        // with the model name echoed. A gateway auth denial would be 401 +
        // KEY_NOT_FOUND instead. (Note: probe bodies must be valid JSON —
        // PowerShell strips quotes from curl -d strings, which yields 400.)
        'gate passed, rejected locally': (r) => r.status === 404 && r.body.includes('unknown model'),
        'gate verdict fast': (r) => r.timings.duration < 2000,
    });
}
