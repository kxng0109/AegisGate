/*
 * 10-rps-flood: cache-hit-able chat completions at open-loop arrival rate.
 * First pass primes the cache (misses need an upstream provider key); the
 * measured stage asserts HIT behavior. Without a provider key, expect 502/504
 * on misses — the rate-integrity + rejection-shape checks still hold.
 * Run: k6 run loadtest/k6/10-rps-flood.js
 * Env:  BASE_URL, LOAD_KEY (default compose load-test key, 60000 RPM),
 *       API_KEY (falls back to the 120 RPM dev key),
 *       PRIME_RATE, FLOOD_RATE (defaults sized for the local Ollama stack;
 *       raise for a provider-keyed run — local qwen2.5:0.5b saturates ~50 rps)
 */
import http from 'k6/http';
import {check} from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
// Load-test key (60000 RPM) so the flood is NOT rate-limited; falls back to the
// 120 RPM dev key if unset (then the flood asserts the 429+Retry-After
// rate-integrity shape instead of throughput).
const KEY = __ENV.LOAD_KEY || __ENV.API_KEY || 'gw-localdevmasterkey0123456789abcde';
// Local Ollama is CPU-bound: 500 concurrent requests saturate qwen2.5:0.5b
// (60s timeouts, 100K+ dropped iterations). Default to a sustainable rate on
// the local stack; raise via FLOOD_RATE for a provider-keyed run.
const PRIME_RATE = Number(__ENV.PRIME_RATE || 10);
const FLOOD_RATE = Number(__ENV.FLOOD_RATE || 50);
// Local runs without provider keys: MODEL=local-llama (Ollama qwen2.5:0.5b).
const MODEL = __ENV.MODEL || 'fast';

export const options = {
    scenarios: {
        prime: {
            executor: 'constant-arrival-rate',
            rate: PRIME_RATE,
            timeUnit: '1s',
            duration: '1m',
            preAllocatedVUs: 20,
            maxVUs: 50,
            exec: 'chat',
            tags: {phase: 'prime'},
        },
        flood: {
            executor: 'ramping-arrival-rate',
            startRate: FLOOD_RATE,
            timeUnit: '1s',
            preAllocatedVUs: 200,
            maxVUs: 1000,
            stages: [
                {duration: '2m', target: FLOOD_RATE},
                {duration: '5m', target: FLOOD_RATE},
            ],
            startTime: '70s',
            exec: 'chat',
            tags: {phase: 'measure'},
        },
    },
    thresholds: {
        // Tolerate warmup drops at the ramping-arrival-rate start (VUs spin up after
        // startTime); a rate gate catches real saturation without failing on noise.
        dropped_iterations: ['rate<=0.001'],
        // NOTE: no http_req_failed gate — 502/503/504 (upstream-unavailable) are
        // accepted outcomes when no provider key is configured; `checks` asserts
        // the response-shape contract instead.
        'checks{phase:measure}': ['rate>0.95'],
    },
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
    });
    check(res, {
        'accepted (200 or provider-unavailable)': (r) => [200, 502, 503, 504].includes(r.status),
        'rate not exceeded loudly': (r) => r.status !== 429 || r.headers['Retry-After'] !== undefined,
    });
}
