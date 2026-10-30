-- 令牌桶限流，整个"补充 + 扣减 + 写回"在 Redis 里原子执行。
-- KEYS[1]  桶的 key（hash：tokens 剩余令牌数，ts 上次补充时间毫秒）
-- ARGV[1]  桶容量（允许的突发量）
-- ARGV[2]  每秒补充的令牌数
-- ARGV[3]  本次需要的令牌数
-- ARGV[4]  扣减后至少要留下的令牌数（后台任务给在线请求预留余量，在线请求传 0）
-- 返回 {是否放行 1/0, 还需等待的毫秒数}
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])
local reserve = tonumber(ARGV[4])

-- 时间取 Redis 服务器时间，多个应用实例之间不受各自时钟偏差影响
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil or ts == nil then
    tokens = capacity
    ts = now
end

tokens = math.min(capacity, tokens + math.max(0, now - ts) * rate / 1000)

local allowed = 0
local wait = 0
if tokens - requested >= reserve then
    tokens = tokens - requested
    allowed = 1
else
    wait = math.ceil((requested + reserve - tokens) * 1000 / rate)
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
-- 桶补满所需时间之后 key 自动过期，过期等价于满桶，不会留下永不过期的 key
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity * 1000 / rate) + 1000)
return {allowed, wait}
