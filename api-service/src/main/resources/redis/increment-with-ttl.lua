-- Increments a counter and, when it is new, gives it a time to live, in ONE atomic step.
-- (Doing INCR and EXPIRE as two separate commands could leave a counter without expiry if
-- the application stopped in between, locking the user out forever.)
--
-- KEYS[1]  counter key
-- ARGV[1]  time to live in milliseconds
-- Returns the new value of the counter.
local count = redis.call('INCR', KEYS[1])
if count == 1 then
  redis.call('PEXPIRE', KEYS[1], ARGV[1])
end
return count
