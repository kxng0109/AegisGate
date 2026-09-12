-- Atomic fixed-window SPEND budget check, per subject level (KEY / TEAM / ORG).
--
-- Executed via Spring Data Redis DefaultRedisScript (EVALSHA; the NOSCRIPT
-- fallback to EVAL is handled transparently by Spring, not by this script).
-- Deliberately SEPARATE from rate_limit.lua: keys without budgets never invoke
-- it (zero overhead on the existing path), and the rate script's tested
-- semantics stay byte-identical.
--
-- KEYS: three dim-groups of three keys each (level order KEY, TEAM, ORG).
--   An empty-string key means "level not configured" and is skipped entirely.
--   KEYS[1..3] = minute spend counters for KEY, TEAM, ORG
--   KEYS[4..6] = month spend counters for KEY, TEAM, ORG
--   KEYS[7..9] = config hashes (HGET minute_micros / month_micros) for KEY, TEAM, ORG
--
-- ARGV:
--   ARGV[1] = estimatedCostMicros integer >= 0; spend this request is expected to add
--
-- Semantics (the anti-double-charge rules that motivated this shape):
--   * CHECK-BEFORE-INCREMENT: a request that would exceed a cap is rejected
--     WITHOUT touching any counter. Post-increment checks (like the rate
--     limiter's attempt counting) would let denied requests inflate spend and
--     re-exhaust budgets — the exact reservation-leak class seen in production
--     gateways. Unlimited dimensions (limit == 0) and zero-cost requests never
--     create keys.
--   * Denial precedence is KEY, then TEAM, then ORG; minute before month inside
--     a level. The first violated dimension wins and later levels are not
--     evaluated (their spend is untouched by a request that will not run).
--   * Month counters carry a 45-day safety TTL (longest month plus margin);
--     calendar rollover is implicit because the engine addresses a new
--     YYYY-MM key each month — there is no reset job to race.
--   * Counters and limits are micro-dollars (integers); floating point never
--     crosses this boundary.
--
-- Return value: EXACTLY 5 integers (never booleans; a Lua false inside a
-- returned table collapses to nil and truncates the array):
--   [1] allowed          1 = within every cap, 0 = rejected
--   [2] rejected         0 = none, 1 = key-minute, 2 = key-month,
--                        3 = team-minute, 4 = team-month, 5 = org-minute, 6 = org-month
--   [3] remainingMicros  lowest (limit - count) across evaluated dims, clamped >= 0;
--                        -1 when no dimension was configured at all
--   [4] resetSeconds     minute-window TTL of the binding dim; 0 when the binding
--                        dim is monthly (the engine resolves month-end itself)
--   [5] configured       count of dimensions with a limit > 0 (lets the engine
--                        cache presence without a second round trip)

local estimated = tonumber(ARGV[1])
if estimated == nil or estimated < 0 then
	estimated = 0
end

-- Counts configured dimensions exactly like the main loop (minute>0 and month>0
-- each count once), for the early-return paths below that must report an honest
-- configured count so the engine's presence cache is never poisoned by a zero.
local function countConfigured()
	local n = 0
	local lvl = 1
	while lvl <= 3 do
		local ck = KEYS[lvl + 6]
		if ck ~= '' then
			if (tonumber(redis.call('HGET', ck, 'minute_micros') or '0') or 0) > 0 then
				n = n + 1
			end
			if (tonumber(redis.call('HGET', ck, 'month_micros') or '0') or 0) > 0 then
				n = n + 1
			end
		end
		lvl = lvl + 1
	end
	return n
end

-- Lua numbers are doubles: integers above 2^53-1 compare and accumulate
-- inexactly, so a corrupt estimate could overflow a counter or misjudge a cap.
-- The engine clamps to this bound, so this is defense-in-depth only: deny without
-- consuming (first-denied-wins is preserved trivially — nothing was evaluated),
-- counting configured dimensions exactly like the main loop so the presence cache
-- is not poisoned by a zero count.
local MAX_EXACT_INTEGER = 9007199254740991
if estimated > MAX_EXACT_INTEGER then
	return { 0, 1, 0, 60, countConfigured() }
end

-- Idempotency: a retried client key must not double-debit. Claim-first with a 24h
-- TTL (Stripe idempotency lifecycle); the loser of a duplicate claim is admitted
-- without spending. The claim key shares the fleet slot tag so the whole script
-- stays single-slot. Absent id means no dedupe (internal callers, legacy clients).
-- Client keys are length- and charset-bounded at the controllers (<= 255 printable
-- ASCII), so the composed key cannot bloat the keyspace per call.
local dedupeId = ARGV[2]
if dedupeId ~= nil and dedupeId ~= '' then
	local dedupeKey = 'budget:{b:global}:dedupe:' .. dedupeId
	if redis.call('SET', dedupeKey, '1', 'NX', 'EX', 86400) == false then
		return { 1, 0, 0, 0, countConfigured() }
	end
end

local rejected = 0
local remaining = -1
local resetSeconds = 0
local configured = 0
local level = 1
while level <= 3 do
	local minuteKey = KEYS[level]
	local monthKey = KEYS[level + 3]
	local cfgKey = KEYS[level + 6]
	if minuteKey ~= '' then
		local minuteLimit = tonumber(redis.call('HGET', cfgKey, 'minute_micros') or '0') or 0
		local monthLimit = tonumber(redis.call('HGET', cfgKey, 'month_micros') or '0') or 0
		if minuteLimit > 0 then
			configured = configured + 1
		end
		if monthLimit > 0 then
			configured = configured + 1
		end
		if minuteLimit > 0 or monthLimit > 0 then
			local minuteCount = tonumber(redis.call('GET', minuteKey) or '0') or 0
			local monthCount = tonumber(redis.call('GET', monthKey) or '0') or 0
			local minuteDenied = minuteLimit > 0 and minuteCount + estimated > minuteLimit
			local monthDenied = monthLimit > 0 and monthCount + estimated > monthLimit
			if minuteDenied or monthDenied then
				rejected = (level - 1) * 2 + 1
				resetSeconds = 60
				if minuteDenied then
					local ttl = redis.call('TTL', minuteKey)
					if ttl ~= nil and ttl > 0 then
						resetSeconds = ttl
					end
				else
					rejected = (level - 1) * 2 + 2
					resetSeconds = 0
				end
				remaining = 0
				break
			end
			if estimated > 0 then
				if minuteLimit > 0 then
					minuteCount = redis.call('INCRBY', minuteKey, estimated)
					local ttl = redis.call('TTL', minuteKey)
					if ttl == nil or ttl < 0 then
						redis.call('EXPIRE', minuteKey, 60)
						ttl = 60
					end
				end
				if monthLimit > 0 then
					monthCount = redis.call('INCRBY', monthKey, estimated)
					if redis.call('TTL', monthKey) < 0 then
						redis.call('EXPIRE', monthKey, 3888000)
					end
				end
			end
			if minuteLimit > 0 then
				local left = minuteLimit - minuteCount
				if remaining < 0 or left < remaining then
					remaining = left
					local ttl = redis.call('TTL', minuteKey)
					if ttl == nil or ttl < 0 then
						ttl = 60
					end
					resetSeconds = ttl
				end
			end
			if monthLimit > 0 then
				local left = monthLimit - monthCount
				if remaining < 0 or left < remaining then
					remaining = left
					resetSeconds = 0
				end
			end
		end
	end
	level = level + 1
end

local allowed = 1
if rejected > 0 then
	allowed = 0
end

return { allowed, rejected, remaining, resetSeconds, configured }
