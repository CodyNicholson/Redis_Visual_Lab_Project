# Redis Visual Lab

An interactive lab that demonstrates Redis features against a real Redis 8 server. A Spring Boot backend talks to PostgreSQL and Redis; a React UI gives every feature its own page with:

- **How it works**: a plain-language explanation and real-world uses
- **Request flow**: an animated diagram of the request moving between the browser, Spring Boot, Redis and Postgres, step by step
- **Buttons** that run the real Redis commands
- **A live visual** of the data (leaderboard bars that re-sort, a lock timeline, a bitmap grid, a geo map, a time-series chart, …)
- **The exact commands sent to Redis** and **self-checks** the backend verified (for example, "Lua sold exactly the available stock")

The headline demo fetches a 50,000-row catalog (products joined with an aggregate over 1,000,000 reviews) straight from Postgres, and then the byte-identical payload through a cache-aside Redis layer.

## Quick start

```bash
docker network create tunnel-net   # once; the frontend joins it for the Cloudflare tunnel
docker compose up -d --build
```

Open <http://localhost:3000>. The first start seeds Postgres (about 7 s); the backend is ready when `docker logs redis-lab-backend` shows `Seeded 50000 products`.

| Service | Container | Host port |
|---|---|---|
| React UI (nginx, proxies `/api`) | `rixus-frontend` | 3000 |
| Spring Boot API | `redis-lab-backend` | 8080 |
| Redis 8.2 | `redis-lab-redis` | 6379 |
| PostgreSQL 16 | `redis-lab-postgres` | 5436 |

`redis-cli -p 6379` works from the host if you want to watch keys while you click (`MONITOR` is fun).

## What the lab covers

| Area | Demo | Redis features |
|---|---|---|
| Caching | Postgres vs Redis cache | cache-aside, TTL, raw byte values, SHA-256 equality, server-side benchmark |
| | Spring `@Cacheable` | `@Cacheable` / `@CachePut` / `@CacheEvict` with `RedisCacheManager`, invalidating dependent cache entries |
| Core data types | Strings | `SET`, `APPEND`, `GETRANGE`, `MSET`/`MGET`, `SET NX`, `INCRBYFLOAT` |
| | Atomic counters | `INCR` vs a racy `GET` + `SET` under concurrent threads |
| | Expiry (TTL) | `EX`, `PTTL`, `PERSIST`, keyspace notifications streamed live |
| | Hashes | `HSET`, `HINCRBY`, `HDEL`, `HGETALL`, per-field `HEXPIRE`/`HPTTL` |
| | Lists | reliable queue with `LPUSH` + `LMOVE`, capped list with `LTRIM` |
| | Sets | `SINTER`, `SUNION`, `SDIFF`, `SISMEMBER`, `SCARD` |
| | Sorted sets | leaderboard: `ZINCRBY`, `ZREVRANGE`, `ZREVRANK`, `ZCOUNT` |
| Patterns | Rate limiter | sliding window in one Lua script over a sorted set |
| | Distributed lock | `SET NX PX` + compare-and-delete Lua release |
| | Transactions | `MULTI`/`EXEC`, `WATCH` optimistic locking with an abort and retry |
| | Lua scripting | flash sale: atomic script vs racy read-modify-write (watch it oversell) |
| | Pipelining | 5,000 commands one by one vs in one batch |
| Messaging | Pub/Sub | `PUBLISH`, `PSUBSCRIBE`, relayed to every open tab over SSE |
| | Streams | `XADD`, consumer groups, `XREADGROUP`, `XACK`, `XPENDING`, `XAUTOCLAIM` recovery |
| Probabilistic & geo | HyperLogLog | `PFADD`/`PFCOUNT` vs an exact set, with error and memory |
| | Bitmaps | `SETBIT`, `BITCOUNT`, `BITOP AND/OR`, raw bytes decoded in the UI |
| | Bloom filter | `BF.RESERVE`, `BF.MADD`, `BF.MEXISTS`, measured false-positive rate |
| | Geospatial | `GEOADD`, `GEOSEARCH` (click the map), `GEODIST`, `GEOHASH` |
| Redis 8 data types | JSON | `JSON.SET`, `JSON.NUMINCRBY`, `JSON.ARRAPPEND`, path updates |
| | Search & query | `FT.CREATE` on JSON, `FT.SEARCH` (text + tag + numeric + `SORTBY`), `FT.AGGREGATE` facets, vs SQL `ILIKE` |
| | Time series | `TS.CREATE` with retention and labels, `TS.MADD`, `TS.RANGE` aggregation, `TS.CREATERULE` compaction |
| | Vector similarity | Vector Sets: `VADD` with attributes, `VSIM` nearest neighbours with `FILTER` |
| Operations | Server | `INFO`, `MODULE LIST`, `SCAN` keyspace map, `TYPE`, `CONFIG GET`, `BGSAVE` |

All lab keys start with `lab:`. **Server → Delete all lab keys** removes them (with `SCAN` + `UNLINK`).

Redis runs with `--appendonly yes` (AOF persistence), `--maxmemory 512mb --maxmemory-policy allkeys-lru` (cache-style eviction) and `--notify-keyspace-events Ex` (expiry events for the TTL demo).

## Architecture

```
Browser ──/api──▶ nginx (rixus-frontend:80) ──▶ Spring Boot (:8080) ──▶ Redis 8 (:6379)
   ▲                                               │        │
   └──── Server-Sent Events (/api/events/stream) ◀─┘        └──▶ PostgreSQL 16 (:5432)
```

- Typed Spring Data Redis APIs (`StringRedisTemplate`, `opsForZSet`, `opsForStream`, `opsForGeo`, Lua `RedisScript`, `SessionCallback` transactions, pipelining) for the core commands.
- `RedisModules` sends commands Spring Data Redis has no API for (`JSON.*`, `FT.*`, `BF.*`, `TS.*`, `V*`, `HEXPIRE`, `XAUTOCLAIM`) over its own Lettuce connection (RESP2, nested-array output).
- Every lab endpoint returns `{ demo, commands, data, checks, elapsedMs }`. The catalog endpoints instead return the raw JSON array, with `X-Cache`, `X-Rows`, `X-Bytes`, `X-Sha256` and `Server-Timing` headers, so a cache hit sends the stored bytes without re-serializing them.

## Tests

**Backend**: 44 JUnit tests. The integration tests start real `postgres:16-alpine` and `redis:8.2-alpine` containers with Testcontainers and call every lab endpoint, failing if any self-check fails. They also assert specific behaviour: byte-identical cache and DB payloads, cache MISS → HIT → invalidation, the rate limiter admitting exactly five requests, consumer-group recovery, key expiry, and search filters. Tests need a Docker socket, so they are skipped in the image build. Run them with:

```bash
cd backend
mvn test            # with a local JDK 21 + Docker
# or, with Docker only:
docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$PWD":/app -w /app -e TESTCONTAINERS_HOST_OVERRIDE=localhost \
  maven:3.9.8-eclipse-temurin-21 mvn -B test
```

**Frontend**: 87 Vitest tests check that every demo has its explanation, valid flow steps, buttons and visual; they also test the API helpers and render every visual.

```bash
cd frontend && npm install && npm test
```

**In the UI**: **▶ Run all demos (self-test)** runs every demo against the live stack and shows pass/fail for each of its checks.

## Local development

```bash
docker compose up -d postgres redis
cd backend && mvn spring-boot:run      # :8080
cd frontend && npm install && npm run dev   # :3000, proxies /api to :8080
```

## Deployment

`pipeline-run.sh` (git-ignored, same as the other workspace labs) pulls the code, removes **all** containers on the host, starts the Cloudflare tunnel on `tunnel-net`, and runs `docker compose up -d --build`. The UI container is named `rixus-frontend` and listens on :80 because the tunnel routes to `http://rixus-frontend:80`. Endpoint inputs are clamped (threads, keys, commands) because the lab can be public.

## Notes

- Timings vary with hardware and JIT warm-up; the first click on a demo is usually slower. Pipelining reaches its full speed-up after a few runs.
- Redis features that need more than one server (replication, Sentinel, Cluster) are not demonstrated.
