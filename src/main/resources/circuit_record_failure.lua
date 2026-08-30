-- Atomic breaker failure recording: counts consecutive failures in CLOSED and
-- trips CLOSED -> OPEN at the threshold; reopens from HALF_OPEN immediately.
-- Single Redis hash key per provider; O(1) and non-blocking.
--
-- KEYS:
--   KEYS[1] = "circuit:{provider}"  hash: state, failures, openedAt, probeOwner, probeStartedAt
--
-- ARGV:
--   ARGV[1] = nowMillis        server-side wall clock supplied by the caller (this instance's epoch millis)
--   ARGV[2] = failureThreshold consecutive failures that trip the circuit
--
-- Returns an integer (ignored by the caller): 1 when state changed, 0 when OPEN.

local state = redis.call('HGET', KEYS[1], 'state') or 'CLOSED'
if state == 'OPEN' then return 0 end
if state == 'HALF_OPEN' then
	redis.call('HSET', KEYS[1], 'state', 'OPEN', 'openedAt', ARGV[1], 'failures', 0)
	return 1
end
local fail = redis.call('HINCRBY', KEYS[1], 'failures', 1)
redis.call('HSET', KEYS[1], 'state', 'CLOSED')
if fail >= tonumber(ARGV[2]) then
	redis.call('HSET', KEYS[1], 'state', 'OPEN', 'openedAt', ARGV[1])
end
return 1