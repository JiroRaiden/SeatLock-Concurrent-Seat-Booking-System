--[[
=============================================================================
 release_holds.lua - hand seats back, but ONLY the ones we still own.

 KEYS[1..n]  seat hold keys
 ARGV[1]     the hold token we believe we own

 RETURNS     the number of keys actually released.

 -----------------------------------------------------------------------------
 WHY THIS IS NOT JUST `DEL`
 -----------------------------------------------------------------------------
 This is the single most important safety detail in the hold mechanism, and it
 is the bug that distributed-lock implementations get wrong most often.

 Consider a plain DEL, and this timeline:

   t=0     Alice holds seat A5 with an 8 minute TTL.
   t=8m    Alice's TTL expires. Redis deletes the key. A5 is free.
   t=8m+1s Bob acquires A5. The key now holds BOB's token.
   t=8m+2s Alice's browser finally fires its "cancel my hold" request,
           which had been stuck on a bad connection.
           DEL seat:A5  ->  Alice deletes BOB's hold.
   t=8m+3s Carol acquires A5. Bob and Carol now both believe they hold A5.

 Alice released a lock she no longer owned. The compare-and-delete below makes
 that impossible: we only delete when the stored value is still our token.

 The token acts as a fencing value. This is exactly why the Redlock discussion
 insists a lock must carry a unique owner id, and why `SET key value NX PX` is
 the correct primitive rather than `SETNX` plus a separate `EXPIRE`.

 -----------------------------------------------------------------------------
 WHY A SCRIPT RATHER THAN GET-THEN-DEL IN JAVA
 -----------------------------------------------------------------------------
 Doing it in Java would be:

     if (redis.get(key).equals(token)) redis.del(key);      <- WRONG

 Between the GET and the DEL, the TTL can expire and someone else can acquire.
 We would then delete their key, which is the exact bug we set out to avoid.
 The check and the delete must be one atomic step, which means a script.
=============================================================================
--]]

local token    = ARGV[1]
local released = 0

for i = 1, #KEYS do
    if redis.call('GET', KEYS[i]) == token then
        redis.call('DEL', KEYS[i])
        released = released + 1
    end
    -- No else branch on purpose. A key that is missing (already expired) or
    -- owned by somebody else is not an error - releasing is best-effort and
    -- must be safe to call twice. Idempotent cleanup is what lets us call this
    -- from a catch block without worrying about the state we are cleaning up.
end

return released
