-- Atomic, per-key RPM + TPM fixed-window rate limiter.
--
-- Executed via Spring Data Redis DefaultRedisScript (EVALSHA; the NOSCRIPT
-- fallback to EVAL is handled transparently by Spring, not by this script).
--
-- KEYS:
--   KEYS[1] = "ratelimit:rpm:{hashHex}"  request counter for the API key
--   KEYS[2] = "ratelimit:tpm:{hashHex}"  token counter for the API key
--
-- ARGV:
--   ARGV[1] = rpmLimit        integer; requests allowed per window (0 = unlimited)
--   ARGV[2] = estimatedTokens integer >= 1; tokens consumed by this request
--   ARGV[3] = tpmLimit        integer; tokens allowed per window (0 = unlimited)
--   ARGV[4] = windowMillis    integer; fixed-window length in ms (60000)
--
-- Semantics:
--   * A dimension is only touched when its limit is > 0. Unlimited dimensions
--     (limit == 0) never INCR/INCRBY/EXPIRE and never create Redis keys.
--   * Counters are fixed-window: the counter key is EXPIREd to windowSec whole
--     seconds on first use, and re-armed if it ever lacks a TTL.
--   * The decision is computed from the RAW counts before any clamping;
--     RPM rejection wins over TPM rejection when both limits are exceeded.
--   * Remaining budgets are clamped to >= 0; reset seconds are at least 1.
--
-- Return value: EXACTLY 6 integers (never booleans; a Lua false inside a
-- returned table collapses to nil and truncates the array):
--   [1] allowed          1 = within limits, 0 = rejected
--   [2] rpmRemaining     max(0, rpmLimit - rpmCount); 0 when rpmLimit == 0
--   [3] rpmResetSeconds  TTL of the RPM counter, >= 1; windowSec when rpmLimit == 0
--   [4] tpmRemaining     max(0, tpmLimit - tpmCount); 0 when tpmLimit == 0
--   [5] tpmResetSeconds  TTL of the TPM counter, >= 1; windowSec when tpmLimit == 0
--   [6] rejected         1 = RPM exceeded, 2 = TPM exceeded, 0 = neither

local rpmLimit = tonumber(ARGV[1])
local estimatedTokens = tonumber(ARGV[2])
local tpmLimit = tonumber(ARGV[3])
local windowSec = math.max(1, math.floor(tonumber(ARGV[4]) / 1000))

-- Guard: the engine clamps before calling, but never let a bad value reach INCRBY.
if estimatedTokens < 1 then
	estimatedTokens = 1
end

-- RPM counter (only when a request limit is configured).
local rpmCount = 0
local rpmTtl = windowSec
if rpmLimit > 0 then
	rpmCount = redis.call('INCR', KEYS[1])
	rpmTtl = redis.call('TTL', KEYS[1])
	if rpmCount == 1 then
		redis.call('EXPIRE', KEYS[1], windowSec)
		rpmTtl = windowSec
	elseif rpmTtl < 0 then
		-- Counter exists without a TTL (e.g. restored from RDB/AOF); re-arm it.
		redis.call('EXPIRE', KEYS[1], windowSec)
		rpmTtl = windowSec
	end
end

-- TPM counter (only when a token limit is configured); first use is detected
-- by the INCRBY result equalling the amount added.
local tpmCount = 0
local tpmTtl = windowSec
if tpmLimit > 0 then
	tpmCount = redis.call('INCRBY', KEYS[2], estimatedTokens)
	tpmTtl = redis.call('TTL', KEYS[2])
	if tpmCount == estimatedTokens then
		redis.call('EXPIRE', KEYS[2], windowSec)
		tpmTtl = windowSec
	elseif tpmTtl < 0 then
		redis.call('EXPIRE', KEYS[2], windowSec)
		tpmTtl = windowSec
	end
end

-- Decision from RAW counts BEFORE clamping. RPM rejection wins over TPM.
local rejected = 0
if rpmLimit > 0 and rpmCount > rpmLimit then
	rejected = 1
elseif tpmLimit > 0 and tpmCount > tpmLimit then
	rejected = 2
end

local allowed = 1
if rejected > 0 then
	allowed = 0
end

-- Remaining budget and reset TTL, per limited dimension; unlimited dimensions
-- report 0 remaining and the full window as reset.
local rpmRemaining = 0
local rpmResetSeconds = windowSec
if rpmLimit > 0 then
	rpmRemaining = math.max(0, rpmLimit - rpmCount)
	rpmResetSeconds = math.max(1, rpmTtl)
end

local tpmRemaining = 0
local tpmResetSeconds = windowSec
if tpmLimit > 0 then
	tpmRemaining = math.max(0, tpmLimit - tpmCount)
	tpmResetSeconds = math.max(1, tpmTtl)
end

return { allowed, rpmRemaining, rpmResetSeconds, tpmRemaining, tpmResetSeconds, rejected }