--[[
=============================================================================
 extend_holds.lua - push the expiry out, but only if we still own EVERY seat.

 KEYS[1..n]  seat hold keys
 ARGV[1]     our hold token
 ARGV[2]     the new remaining time-to-live, in milliseconds

 RETURNS     the number of keys extended, or -1 if we no longer own all of them.

 -----------------------------------------------------------------------------
 WHY ALL-OR-NOTHING AGAIN
 -----------------------------------------------------------------------------
 A hold covers a set of seats the user intends to buy together. If we have lost
 even one of them, extending the rest is actively harmful: it keeps seats out of
 sale for a booking that can no longer complete. Better to report failure so the
 caller can release everything and tell the user honestly.

 Same two-pass structure as acquire: verify ownership of all keys first, then
 apply PEXPIRE. If we extended as we went and hit a lost seat halfway through,
 we would leave some seats extended and some not - a state no caller can reason
 about.

 -----------------------------------------------------------------------------
 PEXPIRE, NOT SET
 -----------------------------------------------------------------------------
 PEXPIRE changes only the TTL and leaves the value alone. Re-SETting would also
 work here, but PEXPIRE states the intent precisely: we are not re-claiming the
 seat, we are adjusting how long our existing claim lasts.
=============================================================================
--]]

local token  = ARGV[1]
local ttl_ms = tonumber(ARGV[2])

if ttl_ms == nil or ttl_ms <= 0 then
    return redis.error_reply('extend_holds: ttl must be a positive integer')
end

-- Pass 1: ownership check. A missing key (expired) is also a loss.
for i = 1, #KEYS do
    if redis.call('GET', KEYS[i]) ~= token then
        return -1
    end
end

-- Pass 2: apply the new TTL to every key.
local extended = 0
for i = 1, #KEYS do
    redis.call('PEXPIRE', KEYS[i], ttl_ms)
    extended = extended + 1
end

return extended
