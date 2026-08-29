-- Per model pricing table, populated by PricingSyncService from the LiteLLM
-- pricing catalog and seeded here with the models the gateway is configured
-- to serve. Prices are USD per token, kept as fixed point decimals.
CREATE TABLE model_pricing
(
    model_id                        VARCHAR(128) PRIMARY KEY,
    litellm_provider                VARCHAR(64)     NOT NULL,
    mode                            VARCHAR(32)     NOT NULL,
    input_cost_per_token            NUMERIC(24, 12) NOT NULL,
    output_cost_per_token           NUMERIC(24, 12) NOT NULL,
    cache_read_input_token_cost     NUMERIC(24, 12),
    cache_creation_input_token_cost NUMERIC(24, 12),
    max_input_tokens                BIGINT,
    max_output_tokens               BIGINT,
    source_url                      VARCHAR(512)    NOT NULL,
    updated_at                      TIMESTAMPTZ     NOT NULL
);

INSERT INTO model_pricing
(model_id, litellm_provider, mode, input_cost_per_token, output_cost_per_token,
 cache_read_input_token_cost, cache_creation_input_token_cost,
 max_input_tokens, max_output_tokens, source_url, updated_at)
VALUES ('gpt-5.6-luna', 'openai', 'chat', 0.0000002, 0.0000012, 0.00000002, 0.00000025, 922000, 128000,
        'https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json', now()),
       ('gpt-5.6-terra', 'openai', 'chat', 0.000002, 0.000012, 0.0000002, 0.0000025, 922000, 128000,
        'https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json', now()),
       ('gpt-5.6-sol', 'openai', 'chat', 0.000004, 0.00002, 0.0000004, 0.000005, 922000, 128000,
        'https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json', now()),
       ('claude-sonnet-5', 'anthropic', 'chat', 0.000002, 0.00001, 0.0000002, 0.0000025, 1000000, 128000,
        'https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json', now()),
       ('claude-opus-5', 'anthropic', 'chat', 0.000005, 0.000025, NULL, NULL, 1000000, 128000,
        'https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json', now()),
       ('claude-haiku-4-5', 'anthropic', 'chat', 0.000001, 0.000005, NULL, NULL, 200000, 64000,
        'https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json', now()),
       ('ollama/llama3.2', 'ollama', 'chat', 0.0, 0.0, NULL, NULL, NULL, NULL,
        'https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json', now());