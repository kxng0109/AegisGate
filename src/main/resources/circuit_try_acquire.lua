-- Atomic breaker admission: decides whether an attempt may proceed right now,
-- and performs the CLOSED -> OPEN -> HALF_OPEN transitions. Single Redis hash
-- key per provider (no separate probe key, so no cross-slot pitfalls in Redis
-- Cluster). Time is read from the Redis server (TIME) so cooldown math never
-- depends on a gateway instance's local clock.
--
-- KEYS:
--   KEYS[1] = "circuit:{provider}"  hash: state, failures, openedAt, probeOwner, probeStartedAt
--
-- ARGV:
--   ARGV[1] = instanceId         owner claiming the HALF_OPEN probe on transitions
--   ARGV[2] = cooldownMillis     how long OPEN lasts before a probe is allowed
--   ARGV[3] = failureThreshold   unused here; kept for symmetry with the other scripts
--   ARGV[4] = probeLeaseMillis   max age of a probe before another instance may steal it
--
-- Returns:
--   1 = the caller may probe/call now (CLOSED, or this instance owns a live probe)
--   0 = reject (OPEN before cooldown, or a live probe is owned by another instance)

local now = redis.call('TIME')
local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
local state = redis.call('HGET', KEYS[1], 'state') or 'CLOSED'
if state == 'CLOSED' then
	return 1
end
if state == 'OPEN' then
	local opened = tonumber(redis.call('HGET', KEYS[1], 'openedAt') or '0')
	if nowMs - opened >= tonumber(ARGV[2]) then
		redis.call('HSET', KEYS[1], 'state', 'HALF_OPEN', 'failures', 0, 'probeOwner', ARGV[1], 'probeStartedAt', nowMs)
		return 1
	end
	return 0
end
local owner = redis.call('HGET', KEYS[1], 'probeOwner')
if owner == ARGV[1] then
	return 1
end
local started = tonumber(redis.call('HGET', KEYS[1], 'probeStartedAt') or '0')
if nowMs - started >= tonumber(ARGV[4]) then
	redis.call('HSET', KEYS[1], 'probeOwner', ARGV[1], 'probeStartedAt', nowMs)
	return 1
end
return 0