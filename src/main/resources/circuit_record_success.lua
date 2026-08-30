-- Atomic breaker success recording: HALF_OPEN success closes the circuit and
-- resets the failure count; CLOSED success just resets the count. Single Redis
-- hash key per provider; O(1) and non-blocking.
--
-- KEYS:
--   KEYS[1] = "circuit:{provider}"  hash: state, failures, openedAt, probeOwner, probeStartedAt
--
-- ARGV: none.
--
-- Returns an integer (ignored by the caller): 1 when state changed or reset, 0 when OPEN.

local state = redis.call('HGET', KEYS[1], 'state') or 'CLOSED'
if state == 'HALF_OPEN' then
	redis.call('HSET', KEYS[1], 'state', 'CLOSED', 'failures', 0)
	return 1
end
if state == 'CLOSED' then
	redis.call('HSET', KEYS[1], 'failures', 0)
	return 1
end
return 0