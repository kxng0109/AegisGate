/*
 * 20-embeddings-burst: single-string embeddings at arrival rate.
 * PREREQUISITE: an upstream embedding provider key (OPENAI_API_KEY etc.) —
 * embeddings have no cache and always call upstream. Without one, expect
 * 502/504 (transport still proves rate integrity, not correctness).
 * Run: k6 run loadtest/k6/20-embeddings-burst.js
 * Env:  BASE_URL, LOAD_KEY (default compose load-test key, 60000 RPM),
 *       API_KEY (falls back to the 120 RPM dev key),
 *       EMBEDDINGS_MODEL (default local-embed on the local Ollama stack),
 *       BURST_RATE (default 20 for local nomic-embed-text; raise for a
 *       provider-keyed run — local CPU saturates ~20 rps)
 */
import http from 'k6/http';
import {check} from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const KEY = __ENV.LOAD_KEY || __ENV.API_KEY || 'gw-localdevmasterkey0123456789abcde';
const MODEL = __ENV.EMBEDDINGS_MODEL || 'text-embedding-3-small';
// Local Ollama nomic-embed-text is CPU-bound: 100 concurrent saturates it
// (4.7s median, 6.8K dropped). Default to a sustainable rate on the local
// stack; raise via BURST_RATE for a provider-keyed run.
const BURST_RATE = Number(__ENV.BURST_RATE || 20);

export const options = {
    scenarios: {
        burst: {
            executor: 'ramping-arrival-rate',
            startRate: BURST_RATE,
            timeUnit: '1s',
            preAllocatedVUs: 50,
            maxVUs: 300,
            stages: [
                {duration: '1m', target: BURST_RATE},
                {duration: '3m', target: BURST_RATE},
            ],
            exec: 'embed',
            tags: {flow: 'embeddings'},
        },
    },
    thresholds: {
        // Tolerate warmup drops at the ramping-arrival-rate start; the rate gate
        // catches real saturation without failing on VU spin-up noise.
        dropped_iterations: ['rate<=0.001'],
        // NOTE: no http_req_failed gate — 502/503/504 (no provider key) are accepted
        // outcomes; `checks` asserts the response-shape contract instead.
        'http_req_duration{flow:embeddings}': ['p(95)<2000'],
        checks: ['rate>0.95'],
    },
    discardResponseBodies: true,
};

export function embed() {
    const res = http.post(
        BASE + '/v1/embeddings',
        JSON.stringify({model: MODEL, input: 'Hello world'}),
        {
            headers: {'Content-Type': 'application/json', Authorization: 'Bearer ' + KEY},
            tags: {flow: 'embeddings'},
        }
    );
    check(res, {
        'accepted (200 or provider-unavailable)': (r) => [200, 502, 503, 504].includes(r.status),
    });
}
