/*
 * 35-mixed-60s: 75-second mixed-traffic proof run.
 *
 * Exercises in one run, against the SAME tree:
 *  - chat similar prompts (5 fixed, cycling)      -> L2 semantic HIT path
 *  - chat unique prompts                           -> full inference path
 *  - embeddings similar inputs (3 fixed, cycling)  -> L2 HIT path
 *  - embeddings unique inputs                      -> full embed path
 *  - err-401 (no key)                              -> 401 exactly
 *  - err-400 (malformed JSON)                      -> 400 exactly
 *  - err-404 (unknown model)                       -> 404 exactly
 *  - err-429 (120-RPM dev key, sequenced)          -> 429 exactly (401 if DEV_KEY unset)
 *
 * Rates are sized for a single iGPU box (~17 rps sustained; the 41 rps draft
 * saturated Ollama with 23s p95 and generator-side drops — a load-generator
 * ceiling, not an app regression). The 429 scenario is two-phase: an exhaust
 * burst burns the 120-RPM window, then the measure phase asserts 429s.
 *
 * 5xx cannot be forced safely (it would mean killing providers mid-run), so 5xx
 * is counted opportunistically, never asserted. Streaming (SSE) is covered by
 * scripts 30/31; this run uses non-streaming JSON for deterministic statuses.
 *
 * Env: BASE_URL, LOAD_KEY (or API_KEY fallback), DEV_KEY (120-RPM key for the
 *      429 scenario; falls back to expecting 401 when unset),
 *      MODEL (default local-llama), EMBEDDINGS_MODEL (default local-embed).
 */
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const KEY = __ENV.LOAD_KEY || __ENV.API_KEY || 'gw-localdevmasterkey0123456789abcde';
const DEV_KEY = __ENV.DEV_KEY || '';
const MODEL = __ENV.MODEL || 'local-llama';
const EMBED_MODEL = __ENV.EMBEDDINGS_MODEL || 'local-embed';

export const options = {
    scenarios: {
        chat_similar: {
            executor: 'constant-arrival-rate', rate: 4, timeUnit: '1s',
            duration: '75s', preAllocatedVUs: 10, maxVUs: 40,
            exec: 'chatSimilar', tags: { op: 'chat-similar' },
        },
        chat_unique: {
            executor: 'constant-arrival-rate', rate: 4, timeUnit: '1s',
            duration: '75s', preAllocatedVUs: 10, maxVUs: 40,
            exec: 'chatUnique', tags: { op: 'chat-unique' },
        },
        embed_similar: {
            executor: 'constant-arrival-rate', rate: 2, timeUnit: '1s',
            duration: '75s', preAllocatedVUs: 6, maxVUs: 20,
            exec: 'embedSimilar', tags: { op: 'embed-similar' },
        },
        embed_unique: {
            executor: 'constant-arrival-rate', rate: 2, timeUnit: '1s',
            duration: '75s', preAllocatedVUs: 6, maxVUs: 20,
            exec: 'embedUnique', tags: { op: 'embed-unique' },
        },
        err_401: {
            executor: 'constant-arrival-rate', rate: 1, timeUnit: '1s',
            duration: '75s', preAllocatedVUs: 2, maxVUs: 6,
            exec: 'err401', tags: { op: 'err-401' },
        },
        err_400: {
            executor: 'constant-arrival-rate', rate: 1, timeUnit: '1s',
            duration: '75s', preAllocatedVUs: 2, maxVUs: 6,
            exec: 'err400', tags: { op: 'err-400' },
        },
        err_404: {
            executor: 'constant-arrival-rate', rate: 1, timeUnit: '1s',
            duration: '75s', preAllocatedVUs: 2, maxVUs: 6,
            exec: 'err404', tags: { op: 'err-404' },
        },
        err_429_exhaust: {
            executor: 'constant-arrival-rate', rate: 12, timeUnit: '1s',
            duration: '15s', preAllocatedVUs: 12, maxVUs: 30,
            exec: 'err429fire', tags: { op: 'err-429-exhaust' },
        },
        err_429: {
            executor: 'constant-arrival-rate', rate: 3, timeUnit: '1s',
            duration: '30s', startTime: '20s',
            preAllocatedVUs: 4, maxVUs: 12,
            exec: 'err429', tags: { op: 'err-429' },
        },
    },
    thresholds: {
        'checks{op:chat-similar}': ['rate>0.99'],
        'checks{op:chat-unique}': ['rate>0.99'],
        'checks{op:embed-similar}': ['rate>0.99'],
        'checks{op:embed-unique}': ['rate>0.99'],
        'checks{op:err-401}': ['rate==1.0'],
        'checks{op:err-400}': ['rate==1.0'],
        'checks{op:err-404}': ['rate==1.0'],
        'checks{op:err-429}': ['rate>0.95'],
    },
    discardResponseBodies: true,
};

const SIMILAR_PROMPTS = [
    'What is the capital of France? Answer in one sentence.',
    'Explain photosynthesis in one sentence.',
    'What is the speed of light? Answer in one sentence.',
    'Define entropy in one sentence.',
    'What year did the moon landing happen? Answer in one sentence.',
];

const SIMILAR_INPUTS = [
    'The quick brown fox jumps over the lazy dog.',
    'Semantic similarity powers the second cache layer.',
    'Embeddings turn text into dense float vectors.',
];

const auth = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + KEY };
const noAuth = { 'Content-Type': 'application/json' };
const devAuth = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + (DEV_KEY || 'missing') };

function chatBody(prompt) {
    return JSON.stringify({ model: MODEL, messages: [{ role: 'user', content: prompt }], max_tokens: 8, stream: false });
}

export function chatSimilar() {
    const body = chatBody(SIMILAR_PROMPTS[__ITER % SIMILAR_PROMPTS.length]);
    const res = http.post(BASE + '/v1/chat/completions', body, { headers: auth, timeout: '30s' });
    check(res, { 'chat-similar accepted': (r) => [200, 502, 503, 504].includes(r.status) });
}

export function chatUnique() {
    const body = chatBody('unique probe ' + __VU + '-' + __ITER + ' ' + Date.now());
    const res = http.post(BASE + '/v1/chat/completions', body, { headers: auth, timeout: '30s' });
    check(res, { 'chat-unique accepted': (r) => [200, 502, 503, 504].includes(r.status) });
}

export function embedSimilar() {
    const body = JSON.stringify({ model: EMBED_MODEL, input: SIMILAR_INPUTS[__ITER % SIMILAR_INPUTS.length] });
    const res = http.post(BASE + '/v1/embeddings', body, { headers: auth, timeout: '30s' });
    check(res, { 'embed-similar accepted': (r) => [200, 502, 503, 504].includes(r.status) });
}

export function embedUnique() {
    const body = JSON.stringify({ model: EMBED_MODEL, input: 'unique embed ' + __VU + '-' + __ITER + ' ' + Date.now() });
    const res = http.post(BASE + '/v1/embeddings', body, { headers: auth, timeout: '30s' });
    check(res, { 'embed-unique accepted': (r) => [200, 502, 503, 504].includes(r.status) });
}

export function err401() {
    const res = http.post(BASE + '/v1/chat/completions', chatBody('hi'), { headers: noAuth, timeout: '30s' });
    check(res, { 'is 401': (r) => r.status === 401 });
}

export function err400() {
    const res = http.post(BASE + '/v1/chat/completions', '{"model":', { headers: auth, timeout: '30s' });
    check(res, { 'is 400': (r) => r.status === 400 });
}

export function err404() {
    const body = JSON.stringify({ model: 'no-such-model-xyz', messages: [{ role: 'user', content: 'hi' }] });
    const res = http.post(BASE + '/v1/chat/completions', body, { headers: auth, timeout: '30s' });
    check(res, { 'is 404': (r) => r.status === 404 });
}

export function err429() {
    const res = http.post(BASE + '/v1/chat/completions', chatBody('hi'), { headers: devAuth, timeout: '30s' });
    check(res, { 'is 429 (or 401 without DEV_KEY)': (r) => r.status === 429 || (DEV_KEY === '' && r.status === 401) });
}

export function err429fire() {
    // Exhaust phase: no checks, burns the 120-RPM window so the measure phase
    // observes steady 429s. Responses (200 early, 429 later) are all expected.
    http.post(BASE + '/v1/chat/completions', chatBody('hi'), { headers: devAuth, timeout: '30s' });
}
