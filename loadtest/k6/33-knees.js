/*
 * 33-knees: concurrency knees hunt against the host-run jar (no container,
 * no cgroup CPU cap, no NAT hop). Fixed concurrency per step — concurrency,
 * not arrival rate, is what bends thread pools and queues.
 *
 * Env:  BASE_URL, LOAD_KEY (must be unlimited: RPMLIMIT=0),
 *       MODEL (default local-llama).
 *
 * Same byte-fixed BODY as the flood scripts: after the L2 prime every request
 * is a semantic-cache HIT (no Ollama call). No failing thresholds on purpose:
 * saturation is the outcome under test, recorded — not asserted.
 */
import http from 'k6/http';
import {check} from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const KEY = __ENV.LOAD_KEY || __ENV.API_KEY || 'gw-localdevmasterkey0123456789abcde';
const MODEL = __ENV.MODEL || 'local-llama';

function steady(name, vus, duration, start) {
    return {
        executor: 'constant-vus',
        vus: vus,
        duration: duration,
        startTime: start,
        exec: 'chat',
        tags: {phase: name},
    };
}

export const options = {
    scenarios: {
        knees500: steady('knees500', 500, '60s', '0s'),
        knees1000: steady('knees1000', 1000, '60s', '70s'),
        knees2000: steady('knees2000', 2000, '90s', '140s'),
        knees4000: steady('knees4000', 4000, '90s', '240s'),
    },
    thresholds: {},
    discardResponseBodies: true,
};

const BODY = JSON.stringify({
    model: MODEL,
    messages: [{role: 'user', content: 'Hello'}],
    max_tokens: 8,
});

export function chat() {
    const res = http.post(BASE + '/v1/chat/completions', BODY, {
        headers: {'Content-Type': 'application/json', Authorization: 'Bearer ' + KEY},
        timeout: '30s',
    });
    check(res, {
        'accepted (200 or provider-unavailable)': (r) => [200, 502, 503, 504].includes(r.status),
        'rate not exceeded loudly': (r) => r.status !== 429 || r.headers['Retry-After'] !== undefined,
    });
}
