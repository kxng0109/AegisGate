-- Hold-record creation for hold-then-settle accounting. Runs once per admitted
-- streaming request, AFTER budget_limit.lua allowed it (denied requests consume
-- nothing and never reach here, so no orphan holds exist on the deny path).
--
-- Executed via Spring Data Redis DefaultRedisScript (EVALSHA; NOSCRIPT fallback
-- handled by Spring). Deliberately SEPARATE from budget_limit.lua, whose tested
-- semantics stay byte-identical: admission charges H into the counters, this
-- script only records the metadata settlement needs to true it up later.
--
-- KEYS:
--   KEYS[1] = hold hash key  (budget:{b:global}:hold:<holdId>)
--   KEYS[2] = expiry index   (budget:{b:global}:hold-expiry, ZSET score = epoch seconds)
--
-- ARGV:
--   ARGV[1] = held micros H (integer >= 0, already clamped by the engine)
--   ARGV[2] = subject ref (level and subject encoded by the engine)
--   ARGV[3] = orig month "YYYY-MM" (admission month; rollover detection compares at settle)
--   ARGV[4] = hold TTL seconds (positive integer)
--   ARGV[5] = created epoch seconds (engine clock; liveness bound only, never money)
--
-- Return value: EXACTLY 2 integers:
--   [1] created  1 = hold record written, 0 = a hold with this id already exists
--   [2] ttl      the TTL applied to the hold record
--
-- Idempotency: same holdId twice (client retry with deterministic request id)
-- returns {0, ttl} without touching counters or the expiry index.

local holdKey = KEYS[1]
if redis.call('EXISTS', holdKey) == 1 then
	return { 0, tonumber(redis.call('TTL', holdKey) or '-1') or -1 }
end

local ttl = tonumber(ARGV[4])
if ttl == nil or ttl <= 0 then
	ttl = 3600
end
local created = tonumber(ARGV[5]) or 0

redis.call('HSET', holdKey,
	'subject', ARGV[2],
	'amount', ARGV[1],
	'state', 'HOLD',
	'orig_month', ARGV[3],
	'created', created)
redis.call('EXPIRE', holdKey, ttl)
redis.call('ZADD', KEYS[2], created + ttl, holdKey)

return { 1, ttl }
