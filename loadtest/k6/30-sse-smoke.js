/*
 * 30-sse-smoke: stock-k6 stream acceptance (NO TTFT measurement — core http
 * buffers the full body; this proves acceptance + close behavior only).
 * For real TTFT/inter-event gaps see 31-sse-hold-xk6.js (needs custom binary).
 * Run: k6 run loadtest/k6/30-sse-smoke.js
 * Env:  BASE_URL, LOAD_KEY (default compose load-test key, 60000 RPM),
 *       API_KEY (falls back to the 120 RPM dev key), MODEL (default local-llama
 *       on the local Ollama stack), SSE_VUS (default 10 for local qwen2.5:0.5b;
  *       raise for a provider-keyed run — local iGPU saturates ~10 concurrent)
 */
import http from 'k6/http';
import {check} from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const KEY = __ENV.LOAD_KEY || __ENV.API_KEY || 'gw-localdevmasterkey0123456789abcde';
// Local runs without provider keys: MODEL=local-llama (Ollama qwen2.5:0.5b).
const MODEL = __ENV.MODEL || 'local-llama';
// Stock-k6 core http buffers the full SSE body, so it aborts a constant
// fraction of its own recycled connections above ~3 concurrent VUs — a
// harness artifact, not a gateway rejection (3 VUs is 100% clean). Higher
// concurrency with real TTFT belongs in 31-sse-hold-xk6.js.
const SSE_VUS = Number(__ENV.SSE_VUS || 3);

export const options = {
    scenarios: {
        holders: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                {duration: '1m', target: SSE_VUS},
                {duration: '2m', target: SSE_VUS},
            ],
            gracefulStop: '30s',
            exec: 'hold',
        },
    },
    thresholds: {
        checks: ['rate>0.95'],
    },
};

export function hold() {
    const res = http.post(
        BASE + '/v1/chat/completions',
        JSON.stringify({
            model: MODEL,
            messages: [{role: 'user', content: 'Hi'}],
            max_tokens: 8,
            stream: true,
        }),
        {headers: {'Content-Type': 'application/json', Authorization: 'Bearer ' + KEY}}
    );
    check(res, {
        'stream accepted (200 or provider-unavailable)': (r) => [200, 502, 503, 504].includes(r.status),
    });
}
