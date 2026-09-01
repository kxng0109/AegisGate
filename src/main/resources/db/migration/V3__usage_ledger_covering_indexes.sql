-- Covering composite index for tenant-scoped billing queries and analytical aggregations.
-- Allows PostgreSQL 16+ to satisfy queries directly via Index-Only Scans without reading table heap pages.
CREATE INDEX idx_usage_ledger_tenant_period_covering ON usage_ledger (owner_id, created_at DESC) INCLUDE (provider, model, prompt_tokens, completion_tokens, total_tokens, cost_usd_micros, duration_ms);

-- Dedicated index for global administrative range scans across all tenants ordered by timestamp.
CREATE INDEX idx_usage_ledger_created_at_desc ON usage_ledger (created_at DESC);
