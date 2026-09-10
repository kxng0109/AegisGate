-- Local Ollama pricing: self-hosted models have no per-token cost.
-- Three rows cover every model string the ledger can store for the local stack:
-- the client alias (non-streaming chat), the provider wire id (streaming chat),
-- and the embedding model id. Matching is case-sensitive exact, so each spelling
-- must exist verbatim. Pure INSERT: the sync path only upserts and never deletes,
-- and ON CONFLICT keeps re-runs idempotent.
INSERT INTO model_pricing
(model_id, litellm_provider, mode, input_cost_per_token, output_cost_per_token,
 cache_read_input_token_cost, cache_creation_input_token_cost,
 max_input_tokens, max_output_tokens, source_url, updated_at)
VALUES ('local-llama', 'ollama', 'chat', 0.0, 0.0, NULL, NULL, NULL, NULL, 'local', now()),
       ('qwen2.5:0.5b', 'ollama', 'chat', 0.0, 0.0, NULL, NULL, NULL, NULL, 'local', now()),
       ('nomic-embed-text:latest', 'ollama', 'embedding', 0.0, 0.0, NULL, NULL, NULL, NULL, 'local', now())
ON CONFLICT (model_id) DO NOTHING;
