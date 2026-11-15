-- Saves the latest reading of a device in a Redis hash, unless the stored reading is newer.
--
-- KEYS[1]   hash of the device, e.g. device:nile-fridge-01:latest
-- ARGV[1]   event time of the new reading, in epoch milliseconds
-- ARGV[2]   when we received it (last-seen), ISO-8601
-- ARGV[3..] field, value, field, value, ... of the new reading
--
-- Returns 1 if the reading was saved, 0 if it was ignored because it is older.
--
-- Redis runs a script atomically: no other client can change the hash between our HGET and
-- our HSET, so two writers can never overwrite a newer reading with an older one.

-- We heard from the device, even if this particular reading arrived late.
redis.call('HSET', KEYS[1], 'lastSeenAt', ARGV[2])

local stored = redis.call('HGET', KEYS[1], 'timestampMillis')
if stored and tonumber(stored) > tonumber(ARGV[1]) then
  return 0
end

redis.call('HSET', KEYS[1], 'timestampMillis', ARGV[1], unpack(ARGV, 3))
return 1
