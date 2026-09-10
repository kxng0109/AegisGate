/*
 * 34-witness-60s: 60-second dashboard illumination run.
 *
 * Hits chat (30 rps) + embeddings (10 rps) with unique prompts (cache MISSes)
 * so every traffic-dependent widget lights up: HTTP latency/rate/error panels,
 * rate-limit evaluations, cache requests, ledger writes, token/cost meters,
 * GC/allocation activity, Redis command volume, Postgres writes.
 *
 * Env: BASE_URL, LOAD_KEY (or API_KEY fallback), MODEL (default local-llama),
 *      EMBEDDINGS_MODEL (default local-embed).
 * Record-only thresholds: a failure here is load information, not a gate.
 */
import http from 'k6/http';
import {check} from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const KEY = __ENV.LOAD_KEY || __ENV.API_KEY || 'gw-localdevmasterkey0123456789abcde';
const MODEL = __ENV.MODEL || 'local-llama';
const EMBED_MODEL = __ENV.EMBEDDINGS_MODEL || 'local-embed';

export const options = {
    scenarios: {
        witness_chat: {
            executor: 'constant-arrival-rate',
            rate: 30,
            timeUnit: '1s',
            duration: '60s',
            preAllocatedVUs: 30,
            maxVUs: 100,
            exec: 'chat',
            tags: {op: 'chat'},
        },
        witness_embed: {
            executor: 'constant-arrival-rate',
            rate: 10,
            timeUnit: '1s',
            duration: '60s',
            preAllocatedVUs: 15,
            maxVUs: 50,
            exec: 'embed',
            tags: {op: 'embed'},
        },
    },
    thresholds: {},
    discardResponseBodies: true,
};

const headers = {
    'Content-Type': 'application/json',
    Authorization: 'Bearer ' + KEY,
};

export function chat() {
    // Unique prompt per iteration defeats L0/L1/L2 so the full path executes.
    const body = JSON.stringify({
        model: MODEL,
        messages: [{role: 'user', content: 'witness ' + __ITER + ' ' + Date.now()}],
        max_tokens: 8,
    });
    const res = http.post(BASE + '/v1/chat/completions', body, {headers: headers, timeout: '30s'});
    check(res, {'chat accepted': (r) => [200, 502, 503, 504].includes(r.status)});
}

export function embed() {
    const body = JSON.stringify({
        model: EMBED_MODEL,
        input: 'witness embedding ' + __ITER + ' ' + Date.now(),
    });
    const res = http.post(BASE + '/v1/embeddings', body, {headers: headers, timeout: '30s'});
    check(res, {'embed accepted': (r) => [200, 502, 503, 504].includes(r.status)});
}
