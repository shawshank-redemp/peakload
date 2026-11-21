-- Token bucket, one atomic step per request.
-- KEYS[1] = bucket key, e.g. rl:<client>:<CLASS>
-- ARGV[1] = capacity (tokens), ARGV[2] = refill rate (tokens per second)
-- Returns {allowed, retry_ms}: allowed is 1 or 0; retry_ms is the wait until the next token when denied.
-- Time comes from the Redis server (TIME), never from the application, so all admission instances agree.
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])

local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local last = tonumber(state[2])
if tokens == nil then
  tokens = capacity
  last = now
end

local elapsed = math.max(0, now - last)
tokens = math.min(capacity, tokens + elapsed * rate / 1000)

local allowed = 0
local retry_ms = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  retry_ms = math.ceil((1 - tokens) * 1000 / rate)
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
-- Twice the time to refill from empty: a bucket idle that long is full again, so its state can go.
redis.call('PEXPIRE', KEYS[1], math.ceil(2 * capacity / rate * 1000))

return {allowed, retry_ms}
