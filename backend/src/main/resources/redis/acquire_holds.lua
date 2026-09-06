--[[
=============================================================================
 acquire_holds.lua - reserve every requested seat, or none of them.

 KEYS[1..n]  one Redis key per seat: seatlock:hold:{evt:<eventId>}:<eventSeatId>
 ARGV[1]     the hold token (the booking's public UUID)
 ARGV[2]     time-to-live in milliseconds

 RETURNS     an empty array on success, otherwise the 1-based indices of the
             keys that were already held by someone else. The caller maps those
             indices back to seat ids so the API can tell the user exactly which
             seats it lost.

 -----------------------------------------------------------------------------
 WHY THIS IS A SCRIPT AND NOT A SEQUENCE OF COMMANDS
 -----------------------------------------------------------------------------
 The requirement is all-or-nothing. A user picks four seats together because
 they want to sit together; giving them three is not a partial success, it is a
 failure that has also removed three seats from sale for the next eight minutes.

 The obvious approach fails:

     for each seat:  SET seat NX PX 480000     <- WRONG

 Because seats 1-3 succeed and seat 4 is taken, we now have to undo 1-3. Between
 our failed SET and our cleanup DELETEs, another user sees seats 1-3 as taken and
 walks away. Worse, if the process crashes between those two steps, the seats
 stay locked until the TTL expires.

 MULTI/EXEC does not fix it either: Redis transactions queue commands and run
 them together, but they cannot branch. There is no way to say "if any of these
 fail, run none of them" - EXEC has already committed whatever it queued.

 A Lua script does fix it, because Redis executes scripts atomically: the server
 is single-threaded for command execution and a script runs to completion with no
 other client's command interleaved. So we can safely do a read pass, decide, and
 then a write pass, knowing nothing changed in between.

 -----------------------------------------------------------------------------
 WHY NOT `SET ... NX` INSIDE THE SCRIPT
 -----------------------------------------------------------------------------
 We do an explicit GET and compare instead of relying on NX, so that a client
 retrying with the SAME token re-acquires its own seats and refreshes the TTL
 rather than colliding with itself. Network retries are normal; a retry must not
 look like contention.

 -----------------------------------------------------------------------------
 THE HASH TAG IN THE KEY
 -----------------------------------------------------------------------------
 The key contains {evt:<eventId>} in braces. On a single Redis node this is just
 part of the string. On Redis Cluster it is a hash tag: only the text inside the
 braces is hashed to choose a shard, so every seat of one event lands on the same
 node. That is a hard requirement - a multi-key Lua script whose keys span shards
 is rejected by the cluster. Writing the key this way now means moving to Cluster
 later is a config change, not a redesign.
=============================================================================
--]]

local token   = ARGV[1]
local ttl_ms  = tonumber(ARGV[2])

-- Defensive: a nil or non-numeric TTL would make PX behave unpredictably.
if ttl_ms == nil or ttl_ms <= 0 then
    return redis.error_reply('acquire_holds: ttl must be a positive integer')
end

-- ---------------------------------------------------------------------------
-- Pass 1: read-only. Find every seat already held by a DIFFERENT token.
-- Nothing is written yet, so bailing out here leaves Redis untouched.
-- ---------------------------------------------------------------------------
local conflicts = {}
for i = 1, #KEYS do
    local holder = redis.call('GET', KEYS[i])
    if holder and holder ~= token then
        conflicts[#conflicts + 1] = i
    end
end

if #conflicts > 0 then
    return conflicts
end

-- ---------------------------------------------------------------------------
-- Pass 2: commit. Every seat was free (or already ours), so claim them all.
-- Re-setting a key we already own deliberately refreshes its TTL.
-- ---------------------------------------------------------------------------
for i = 1, #KEYS do
    redis.call('SET', KEYS[i], token, 'PX', ttl_ms)
end

return {}
