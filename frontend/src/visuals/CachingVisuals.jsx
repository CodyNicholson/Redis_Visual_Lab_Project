import React from 'react';
import { Bar, Empty, Stat, formatBytes, formatMs, formatNumber, useNow } from '../components/common.jsx';

const lastOf = (history, actionId) => [...history].reverse().find((h) => h.actionId === actionId);

export function CatalogVisual({ history }) {
  const db = lastOf(history, 'db');
  const cache = lastOf(history, 'cache');
  const bench = lastOf(history, 'bench');
  const latest = [...history].reverse().find((h) => h.demo === 'catalog');
  if (!db && !cache && !bench) {
    return <Empty>Fetch the catalog from Postgres, then through the Redis cache (twice: the first is a MISS that fills the cache). The race below compares them.</Empty>;
  }
  const dbMs = db?.data.timings.total;
  const cacheMs = cache?.data.timings.total;
  const max = Math.max(dbMs || 0, cacheMs || 0);
  const sameSize = db && cache && db.data.size === cache.data.size;
  const identical = sameSize && db.data.sha === cache.data.sha;

  return (
    <div className="catalog-visual">
      <div className="race">
        <h4>Server-side time for the last fetch of each kind</h4>
        {db && <Bar label={`PostgreSQL · ${formatNumber(db.data.rows)} rows`} value={dbMs} max={max} tone="pg" text={formatMs(dbMs)} />}
        {cache && (
          <Bar label={`Redis (${cache.data.cache}) · ${formatNumber(cache.data.rows)} rows`} value={cacheMs} max={max}
            tone={cache.data.cache === 'HIT' ? 'redis' : 'warn'} text={formatMs(cacheMs)} />
        )}
        <div className="race-notes">
          {db && <span>Postgres: query {formatMs(db.data.timings.db)} + serialize {formatMs(db.data.timings.serialize)} · browser {formatMs(db.data.clientMs)}</span>}
          {cache && <span>Redis: {cache.data.cache === 'HIT' ? `read ${formatMs(cache.data.timings.redis)}` : `miss → query ${formatMs(cache.data.timings.db)} + write ${formatMs(cache.data.timings.redisWrite)}`} · browser {formatMs(cache.data.clientMs)}</span>}
        </div>
        {cache?.data.cache === 'MISS' && <p className="hint">That was a cache MISS, so it paid for the query and the Redis write. Fetch from Redis again for a HIT.</p>}
      </div>

      <div className="stat-row">
        {db && cache?.data.cache === 'HIT' && sameSize && <Stat label="faster from Redis" value={`${Math.max(1, Math.round(dbMs / cacheMs))}×`} tone="redis" />}
        {latest && <Stat label="JSON payload" value={formatBytes(latest.data.bytes)} hint={`${formatNumber(latest.data.rows)} rows`} />}
        {sameSize && (
          <Stat label="SHA-256 of both bodies" value={identical ? 'identical ✓' : 'different ✗'} tone={identical ? 'ok' : 'bad'}
            hint={`${db.data.sha.slice(0, 12)}…`} />
        )}
        {db && cache && !sameSize && <Stat label="compare payloads" value="pick one size" hint="Fetch both with the same row count" />}
      </div>

      {bench && (
        <div className="bench">
          <h4>Benchmark: {bench.data.iterations} runs each, {formatNumber(bench.data.rows)} rows, measured on the server</h4>
          <div className="bench-bars">
            {bench.data.dbMs.map((ms, i) => {
              const top = Math.max(...bench.data.dbMs, ...bench.data.cacheMs);
              return (
                <div key={i} className="bench-pair" title={`run ${i + 1}`}>
                  <div className="bench-col bar-pg" style={{ height: `${(ms / top) * 100}%` }} />
                  <div className="bench-col bar-redis" style={{ height: `${Math.max(1, (bench.data.cacheMs[i] / top) * 100)}%` }} />
                </div>
              );
            })}
          </div>
          <div className="stat-row">
            <Stat label="Postgres average" value={formatMs(bench.data.dbAvgMs)} tone="pg" />
            <Stat label="Redis average" value={formatMs(bench.data.cacheAvgMs)} tone="redis" />
            <Stat label="speed-up" value={`${bench.data.speedup}×`} tone="ok" />
          </div>
        </div>
      )}

      {latest?.data.preview?.length > 0 && (
        <div className="table-wrap">
          <h4>First rows of the payload</h4>
          <table>
            <thead><tr><th>id</th><th>name</th><th>category</th><th>price</th><th>reviews</th><th>avg ★</th></tr></thead>
            <tbody>
              {latest.data.preview.map((p) => (
                <tr key={p.id}><td>{p.id}</td><td>{p.name}</td><td>{p.category}</td><td>${p.price.toFixed(2)}</td><td>{p.reviewCount}</td><td>{p.avgRating}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

export function SpringCacheVisual({ result, history }) {
  const now = useNow(500);
  const gets = history.filter((h) => h.actionId === 'get').slice(-12);
  if (!result) return <Empty>Get product 42 twice: the first call runs SQL (MISS), the second is served by Redis (HIT).</Empty>;
  const product = result.data.product;
  const ttlLeft = result.data.ttlSeconds != null && result.receivedAt
    ? Math.max(0, result.data.ttlSeconds - Math.floor((now - result.receivedAt) / 1000)) : null;
  return (
    <div className="spring-visual">
      {product && (
        <div className="product-card">
          {result.actionId === 'get' && (
            <span className={`badge ${result.data.cacheHit ? 'badge-hit' : 'badge-miss'}`}>{result.data.cacheHit ? 'HIT' : 'MISS'}</span>
          )}
          <div className="product-name">{product.name}</div>
          <div className="muted">{product.sku} · {product.category}</div>
          <div className="product-price">${product.price.toFixed(2)}</div>
          <div className="muted">★ {product.avgRating} from {formatNumber(product.reviewCount)} reviews · {product.stock} in stock</div>
          {result.data.ms != null && <div className="product-time">method call took {formatMs(result.data.ms)}</div>}
        </div>
      )}
      <div className="spring-side">
        <div className="key-box">
          <code>{result.data.key}</code>
          {ttlLeft != null && <span className="pill">TTL {ttlLeft}s</span>}
          {result.data.evicted && <span className="pill pill-bad">evicted</span>}
        </div>
        {result.data.cachedJson && <pre className="json-box">{JSON.stringify(JSON.parse(result.data.cachedJson), null, 2)}</pre>}
        {gets.length > 0 && (
          <div className="history-chips">
            {gets.map((g, i) => (
              <span key={i} className={`chip ${g.data.cacheHit ? 'chip-hit' : 'chip-miss'}`}>{g.data.cacheHit ? 'HIT' : 'MISS'} {formatMs(g.data.ms)}</span>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
