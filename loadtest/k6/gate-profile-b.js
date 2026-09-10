/*
 * AegisGate gate profile B (recommended): 80K small-JSON rps + 25K held SSE.
 * Open-loop arrival rate (no coordinated omission) + HDR-aware thresholds.
 *
 * Topology: 4-5 generator hosts (16c/32GB, 10Gbps, unique source IPs) against
 * one SUT (4c/16GB/1Gbps). No single generator proves this target (port, RAM,
 * CPU ceilings) — shard with execution-segment or k6-operator.
 *
 * Run: k6 run --out json=results-B.json loadtest/k6/gate-profile-b.js
 * Requires: __ENV.GATEWAY_URL, __ENV.API_KEY
 */
import http from 'k6/http';
import {check} from 'k6';
import {Trend} from 'k6/metrics';

const ttfb = new Trend('ttfb_ms', true);

export const options = {
    scenarios: {
        // Open-loop small JSON: cache hits / embeddings / auth (~1KB responses).
        small_json: {
            executor: 'ramping-arrival-rate',
            startRate: 10000,
            timeUnit: '1s',
            preAllocatedVUs: 4000,
            maxVUs: 12000,
            stages: [
                {duration: '5m', target: 40000},
                {duration: '5m', target: 80000},
                {duration: '30m', target: 80000},
            ],
        },
        // Closed held SSE: 1 VU = 1 stream, 60-300s holds, explicit close.
        sse_held: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                {duration: '10m', target: 25000},
                {duration: '30m', target: 25000},
            ],
            gracefulStop: '30s',
        },
    },
    thresholds: {
        dropped_iterations: ['count==0'],
        http_req_failed: ['rate<0.001'],
        http_req_duration: ['p(50)<15', 'p(99)<100', 'p(99.9)<250'],
        ttfb_ms: ['p(95)<500'],
    },
};

const BASE = __ENV.GATEWAY_URL;
const HEADERS = {Authorization: 'Bearer ' + __ENV.API_KEY, 'Content-Type': 'application/json'};

export function small_json() {
    const res = http.post(
        BASE + '/v1/chat/completions',
        JSON.stringify({model: 'fast', messages: [{role: 'user', content: 'Hi'}], max_tokens: 8}),
        {headers: HEADERS, tags: {scenario: 'small_json'}}
    );
    check(res, {'status 200': (r) => r.status === 200});
}

export function sse_held() {
    // Held-stream placeholder: production runs use xk6-sse (sse.open/client.on/close)
    // for TTFT + inter-event-gap metrics. Raw http.get hides first-event timing.
    const res = http.get(BASE + '/v1/chat/completions?stream=true', {headers: HEADERS});
    ttfb.add(res.timings.waiting);
    check(res, {'stream accepted': (r) => r.status === 200});
}
