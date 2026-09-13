-- Hold settlement for hold-then-settle accounting: trues the admitted hold H up
-- to actual spend A with exactly-once semantics.
--
-- Executed via Spring Data Redis DefaultRedisScript (EVALSHA; NOSCRIPT fallback
-- handled by Spring). SEPARATE from budget_limit.lua (frozen admission semantics).
--
-- Design notes (do not "optimize" away without re-proving):
--   * Minute counters are NEVER touched here: they are ephemeral admission state
--     (60s TTL) while month counters are accounting truth. A settle-time minute
--     refund could resurrect an expired window with a negative balance; the
--     minute over-hold self-corrects within one window.
--   * Rollover (orig_month != current month): H is refunded to the original
--     month's counters (only when the key still exists — otherwise gap=1) and A
--     is charged to the current month's counters (INCRBY creates a new-month key,
--     which is correct: the response completed now). The gap row records both.
--   * Money moves only on the first touch: the settled-flag claim (SET NX)
--     makes retries idempotent no-ops. Lua numbers are doubles: every amount is
--     guarded against 2^53-1 like budget_limit.lua.
--
-- KEYS:
--   KEYS[1]  = settled flag  (budget:{b:global}:settled:<holdId>)
--   KEYS[2]  = gap marker    (budget:{b:global}:gap:<subject>:<YYYY-MM>)
--   KEYS[3]  = hold hash     (budget:{b:global}:hold:<holdId>)
--   KEYS[4..6]  = orig-month counters KEY, TEAM, ORG ("" skips the level)
--   KEYS[7..9]  = current-month counters KEY, TEAM, ORG ("" skips the level)
--   KEYS[10] = expiry index  (budget:{b:global}:hold-expiry)
--   KEYS[11] = KEY-level cfg hash ("" skips remaining computation)
--
-- ARGV:
--   ARGV[1] = actual micros A (integer >= 0; -1 = expire-only, no settle)
--   ARGV[2] = abort re-arm epoch seconds (0 = none; >0 marks ABORTED and re-arms)
--   ARGV[3] = current month "YYYY-MM" (engine clock; selects the charge keys)
--
-- Return value: EXACTLY 5 integers (same wire discipline as budget_limit.lua):
--   [1] settled           1 = this call moved money or claimed first, 0 = replay/expire
--   [2] outcome           0 = settled, 1 = replay (duplicate), 2 = aborted,
--                         3 = expired (hold missing)
--   [3] amountAppliedMicros actual micros applied (>0 normal; 0 replay/expire)
--   [4] remainingMonthlyMicros KEY-level (limit - count) after settle, -1 when N/A
--   [5] gapSet            1 = gap marker set (Java must persist a gap row), else 0

local MAX_EXACT_INTEGER = 9007199254740991

-- Expire-only path (sweeper): no money, just mark + gap marker. Still claims
-- the settled flag so a racing late settle becomes a replay, not a double move.
if tonumber(ARGV[1]) == -1 then
	if redis.call('SET', KEYS[1], '1', 'NX', 'EX', 86400) == false then
		return { 0, 1, 0, -1, 0 }
	end
	if redis.call('EXISTS', KEYS[3]) == 1 then
		redis.call('HSET', KEYS[3], 'state', 'EXPIRED')
	end
	redis.call('SET', KEYS[2], '1', 'NX', 'EX', 86400)
	redis.call('ZREM', KEYS[10], KEYS[3])
	return { 0, 3, 0, -1, 1 }
end

local actual = tonumber(ARGV[1])
if actual == nil or actual < 0 then
	actual = 0
end
if actual > MAX_EXACT_INTEGER then
	return { 0, 3, 0, -1, 1 }
end

-- First touch wins; a lost claim is an idempotent replay (no money moves twice).
if redis.call('SET', KEYS[1], '1', 'NX', 'EX', 86400) == false then
	return { 0, 1, 0, -1, 0 }
end

local hold = redis.call('HGETALL', KEYS[3])
-- Missing hold record (TTL lapsed before settle): H stays counted (safe
-- over-count direction); the marker tells Java to persist a gap row.
if #hold == 0 then
	redis.call('SET', KEYS[2], '1', 'NX', 'EX', 86400)
	redis.call('ZREM', KEYS[10], KEYS[3])
	return { 0, 3, 0, -1, 1 }
end

local held = 0
local origMonth = ''
local i = 1
while i < #hold do
	if hold[i] == 'amount' then
		held = tonumber(hold[i + 1]) or 0
	elseif hold[i] == 'orig_month' then
		origMonth = hold[i + 1]
	end
	i = i + 2
end
if held < 0 or held > MAX_EXACT_INTEGER then
	redis.call('SET', KEYS[2], '1', 'NX', 'EX', 86400)
	redis.call('ZREM', KEYS[10], KEYS[3])
	return { 0, 3, 0, -1, 1 }
end

local gap = 0
local currMonth = ARGV[3]
local lvl = 1
while lvl <= 3 do
	local origKey = KEYS[lvl + 3]
	local currKey = KEYS[lvl + 6]
	if origKey ~= '' and currKey ~= '' then
		if origMonth == currMonth then
			local delta = actual - held
			if delta ~= 0 then
				redis.call('INCRBY', currKey, delta)
				if redis.call('TTL', currKey) < 0 then
					redis.call('EXPIRE', currKey, 3888000)
				end
			end
		else
			-- Straddle: refund H to the original month only when its key still
			-- exists (resurrecting it with a negative balance would be
			-- fail-open); charge A to the current month (creation is correct).
			if redis.call('EXISTS', origKey) == 1 then
				redis.call('INCRBY', origKey, 0 - held)
			else
				gap = 1
			end
			redis.call('INCRBY', currKey, actual)
			if redis.call('TTL', currKey) < 0 then
				redis.call('EXPIRE', currKey, 3888000)
			end
			gap = 1
		end
	end
	lvl = lvl + 1
end

local function keyRemaining()
	if KEYS[11] == '' or KEYS[7] == '' then
		return -1
	end
	local limit = tonumber(redis.call('HGET', KEYS[11], 'month_micros') or '0') or 0
	if limit <= 0 then
		return -1
	end
	local left = limit - (tonumber(redis.call('GET', KEYS[7]) or '0') or 0)
	if left < 0 then
		left = 0
	end
	return left
end

local abortDue = tonumber(ARGV[2]) or 0
if abortDue > 0 then
	-- Abort: input-known portion settled above; the output hold lapses after
	-- the grace window (the re-armed ZADD score overwrites the crash-expiry
	-- entry). No gap row yet — the sweeper writes it at expiry, if ever.
	redis.call('HSET', KEYS[3], 'state', 'ABORTED')
	redis.call('ZADD', KEYS[10], abortDue, KEYS[3])
	return { 1, 2, actual, keyRemaining(), gap }
end

redis.call('HSET', KEYS[3], 'state', 'SETTLED')
redis.call('ZREM', KEYS[10], KEYS[3])
if gap == 1 then
	redis.call('SET', KEYS[2], '1', 'NX', 'EX', 86400)
end
return { 1, 0, actual, keyRemaining(), gap }
