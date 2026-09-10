/*
 * 32-overload-ramp: gateway ceiling hunt on the cache-hit path.
 *
 * Env:  BASE_URL, LOAD_KEY (must be unlimited: RPMLIMIT=0, see O4),
 *       MODEL (default local-llama on the local Ollama stack).
 *
 * The BODY is byte-identical every iteration (same fixed prompt as
 * 10-rps-flood.js), so after the L2 prime every request is a semantic-cache
 * HIT: no Ollama call, pure gateway path
 * (ingress -> auth -> rate-limit -> RediSearch KNN -> ledger microbatch).
 * That is the only path that can physically exceed iGPU inference limits,
 * so it is the honest ceiling hunt.
 *
 * Five arrival-rate steps with per-step tags; the summary attributes the
 * first break (failed/dropped surge, app restart, host distress) to an exact
 * step. No failing thresholds on purpose: at the top steps saturation is the
 * EXPECTED outcome under test, recorded — not asserted. Abort criteria live
 * outside k6: app OOM/exit, host freeze risk, or operator Ctrl+C.
 * Local runs only: Ollama stays warm but idle on cache hits.
 */
import http from 'k6/http';
import {check} from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const KEY = __ENV.LOAD_KEY || __ENV.API_KEY || 'gw-localdevmasterkey0123456789abcde';

// Local runs without provider keys: MODEL=local-llama (Ollama qwen2.5:0.5b).
const MODEL = __ENV.MODEL || 'local-llama';

function step(name, rate, duration, start, pre, max) {
    return {
        executor: 'ramping-arrival-rate',
        startRate: rate,
        timeUnit: '1s',
        preAllocatedVUs: pre,
        maxVUs: max,
        stages: [{duration: duration, target: rate}],
        startTime: start,
        exec: 'chat',
        tags: {phase: name},
    };
}

export const options = {
    scenarios: {
        overload1k: step('overload1k', 1000, '30s', '0s', 100, 500),
        overload5k: step('overload5k', 5000, '30s', '35s', 300, 1500),
        overload10k: step('overload10k', 10000, '60s', '70s', 600, 2500),
        overload25k: step('overload25k', 25000, '60s', '135s', 1200, 4000),
        overload50k: step('overload50k', 50000, '60s', '200s', 2000, 6000),
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
