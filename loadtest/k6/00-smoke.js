/*
 * 00-smoke: no-key baselines. No provider keys, no bootstrap key, no side effects.
 * Covers: actuator health/prometheus, auth-negative floods (401/404/400/413).
 * Run: k6 run loadtest/k6/00-smoke.js
 * Env:  BASE_URL (default http://localhost:8080)
 */
import http from 'k6/http';
import {check} from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
    vus: 10,
    duration: '1m',
    thresholds: {
        // NOTE: no http_req_failed gate here — 401/4xx rejections ARE the asserted
        // outcomes (3 of 5 requests per iteration). `checks` is the real gate.
        checks: ['rate>0.99'],
    },
    tags: {flow: 'smoke'},
};

export default function () {
    let res = http.get(BASE + '/actuator/health');
    check(res, {'health 200': (r) => r.status === 200});

    res = http.get(BASE + '/actuator/prometheus');
    check(res, {'prometheus 200': (r) => r.status === 200});

    res = http.post(
        BASE + '/v1/chat/completions',
        JSON.stringify({model: 'fast', messages: [{role: 'user', content: 'Hi'}]}),
        {headers: {'Content-Type': 'application/json'}}
    );
    check(res, {'missing key 401': (r) => r.status === 401});

    res = http.post(
        BASE + '/v1/chat/completions',
        JSON.stringify({model: 'no-such-model-xyz', messages: [{role: 'user', content: 'Hi'}]}),
        {headers: {'Content-Type': 'application/json', Authorization: 'Bearer invalid'}}
    );
    check(res, {'bad key still 401 first': (r) => r.status === 401});

    res = http.post(BASE + '/v1/chat/completions', '', {
        headers: {'Content-Type': 'application/json', Authorization: 'Bearer invalid'},
    });
    check(res, {'empty body 4xx': (r) => r.status >= 400 && r.status < 500});
}
