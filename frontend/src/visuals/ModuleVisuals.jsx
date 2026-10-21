import React from 'react';
import { Bar, Empty, Stat, formatMs, formatNumber } from '../components/common.jsx';

/** Flattens a JSON value into { "$.a.b": leaf } so two versions can be compared path by path. */
export function flattenJson(value, path = '$', out = {}) {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    Object.entries(value).forEach(([k, v]) => flattenJson(v, `${path}.${k}`, out));
  } else {
    out[path] = JSON.stringify(value);
  }
  return out;
}

function JsonLines({ value, changed, path = '$', indent = 0, name, last = true }) {
  const pad = '  '.repeat(indent);
  const label = name !== undefined ? `"${name}": ` : '';
  const comma = last ? '' : ',';
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    const entries = Object.entries(value);
    return (
      <>
        <div>{pad}{label}{'{'}</div>
        {entries.map(([k, v], i) => (
          <JsonLines key={k} value={v} changed={changed} path={`${path}.${k}`} indent={indent + 1} name={k} last={i === entries.length - 1} />
        ))}
        <div>{pad}{'}'}{comma}</div>
      </>
    );
  }
  return <div className={changed.has(path) ? 'json-changed' : ''}>{pad}{label}{JSON.stringify(value)}{comma}</div>;
}

export function JsonVisual({ result }) {
  if (!result) return <Empty />;
  const { before, after, key } = result.data;
  const a = flattenJson(before);
  const b = flattenJson(after);
  const changed = new Set(Object.keys(b).filter((p) => a[p] !== b[p]));
  return (
    <div>
      <h4><code>{key}</code>: highlighted lines were changed in place by path commands</h4>
      <div className="json-compare">
        <div><div className="muted">after JSON.SET</div><pre className="json-box"><JsonLines value={before} changed={new Set()} /></pre></div>
        <div><div className="muted">after NUMINCRBY · ARRAPPEND · SET $.plan.tier · NUMMULTBY</div><pre className="json-box"><JsonLines value={after} changed={changed} /></pre></div>
      </div>
    </div>
  );
}

export function SearchVisual({ result }) {
  if (!result) return <Empty>Run a search. The first run copies the products into Redis JSON and indexes them (a few seconds); later runs are instant.</Empty>;
  const d = result.data;
  const maxFacet = Math.max(1, ...d.facets.map((f) => Number(f.count)));
  return (
    <div className="search-visual">
      <div>
        <h4>FT.SEARCH <code>{d.query}</code> → {formatNumber(d.total)} matches in {formatNumber(d.indexedDocs)} documents</h4>
        <div className="table-wrap">
          <table>
            <thead><tr><th>key</th><th>name</th><th>category</th><th>price</th></tr></thead>
            <tbody>
              {d.hits.map((h, i) => (
                <tr key={h.key} className="pop-in" style={{ animationDelay: `${i * 40}ms` }}>
                  <td><code>{h.key}</code></td><td>{h.name}</td><td>{h.category}</td><td>${h.price.toFixed(2)}</td>
                </tr>
              ))}
              {d.hits.length === 0 && <tr><td colSpan="4" className="muted">No matches</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
      <div>
        <h4>Facets (FT.AGGREGATE GROUPBY @category)</h4>
        {d.facets.map((f) => <Bar key={f.category} label={f.category} value={Number(f.count)} max={maxFacet} tone="redis" text={f.count} />)}
        <h4>Same filter</h4>
        <div className="stat-row">
          <Stat label="Redis FT.SEARCH" value={formatMs(d.redisMs)} tone="redis" hint="inverted index" />
          <Stat label="Postgres ILIKE" value={formatMs(d.postgresMs)} tone="pg" hint={`${formatNumber(d.postgresCount)} rows (substring match)`} />
        </div>
      </div>
    </div>
  );
}

export function TimeSeriesVisual({ result }) {
  if (!result) return <Empty />;
  const { raw, avg30m, max30m, compacted } = result.data;
  const W = 640;
  const H = 220;
  const P = 34;
  const t0 = raw[0][0];
  const t1 = raw[raw.length - 1][0];
  const values = [...raw, ...max30m].map((p) => p[1]);
  const v0 = Math.floor(Math.min(...values)) - 1;
  const v1 = Math.ceil(Math.max(...values)) + 1;
  const x = (t) => P + ((t - t0) / Math.max(1, t1 - t0)) * (W - P * 2);
  const y = (v) => H - P + 10 - ((v - v0) / (v1 - v0)) * (H - P * 1.5);
  const line = (points) => points.map(([t, v], i) => `${i ? 'L' : 'M'}${x(t).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
  const ticks = [v0, Math.round((v0 + v1) / 2), v1];
  return (
    <div>
      <svg viewBox={`0 0 ${W} ${H}`} className="ts-chart" role="img" aria-label="Temperature over six hours">
        {ticks.map((v) => (
          <g key={v}>
            <line x1={P} x2={W - P} y1={y(v)} y2={y(v)} className="grid-line" />
            <text x={P - 6} y={y(v) + 4} textAnchor="end" className="axis-text">{v}°</text>
          </g>
        ))}
        <path d={line(raw)} className="ts-raw draw" pathLength="1" />
        <path d={line(max30m)} className="ts-max draw" pathLength="1" />
        <path d={line(avg30m)} className="ts-avg draw" pathLength="1" />
        {avg30m.map(([t, v], i) => <circle key={t} cx={x(t)} cy={y(v)} r="4" className="ts-dot pop-in" style={{ animationDelay: `${600 + i * 60}ms` }} />)}
        <text x={P} y={H - 4} className="axis-text">{new Date(t0).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</text>
        <text x={W - P} y={H - 4} textAnchor="end" className="axis-text">{new Date(t1).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</text>
      </svg>
      <div className="legend">
        <span><i className="swatch ts-raw-swatch" /> raw: {raw.length} samples</span>
        <span><i className="swatch ts-avg-swatch" /> avg per 30 min: {avg30m.length} points</span>
        <span><i className="swatch ts-max-swatch" /> max per 30 min</span>
        <span>compaction rule wrote {compacted.length} closed buckets</span>
      </div>
    </div>
  );
}

export function VectorsVisual({ result }) {
  if (!result) return <Empty>Pick a colour and find its nearest neighbours.</Empty>;
  const d = result.data;
  return (
    <div className="vectors-visual">
      <div className="vector-query">
        <div className="swatch-big" style={{ background: d.query }} />
        <code>{d.query}</code>
        <div className="muted">embedding [{d.embedding.map((n) => n.toFixed(2)).join(', ')}]</div>
        <div className="muted">{d.setSize} colours in <code>lab:vec:colors</code>{d.onlyLight ? ' · FILTER .l >= 0.6' : ''}</div>
      </div>
      <ol className="vector-matches">
        {d.matches.map((m, i) => (
          <li key={m.color} className="pop-in" style={{ animationDelay: `${i * 70}ms` }}>
            <span className="swatch-small" style={{ background: m.color }} />
            <code>{m.color}</code>
            <span className="score-track"><span className="score-fill" style={{ width: `${m.score * 100}%`, background: m.color }} /></span>
            <span className="score">{m.score.toFixed(3)}</span>
          </li>
        ))}
      </ol>
    </div>
  );
}

export function ServerVisual({ result }) {
  if (!result) return <Empty>Inspect the server to see INFO stats, the loaded modules and every lab key grouped by feature.</Empty>;
  const d = result.data;
  if (result.demo === 'reset') {
    return <div className="stat-row"><Stat label="lab keys removed" value={formatNumber(d.removed)} tone="redis" /></div>;
  }
  if (result.demo === 'bgsave') {
    return (
      <div>
        <div className="stat-row"><Stat label="BGSAVE" value={d.reply} tone="ok" /></div>
        <table className="kv-table"><tbody>
          {Object.entries(d.info).map(([k, v]) => <tr key={k}><td><code>{k}</code></td><td>{v}</td></tr>)}
        </tbody></table>
      </div>
    );
  }
  const s = d.stats;
  return (
    <div className="server-visual">
      <div className="stat-row">
        <Stat label="version" value={s.redis_version} />
        <Stat label="memory used" value={s.used_memory_human} hint={`max ${s.maxmemory_human} · ${s.maxmemory_policy}`} tone="redis" />
        <Stat label="hit ratio" value={s.hit_ratio == null ? '–' : `${s.hit_ratio}%`} hint={`${formatNumber(s.keyspace_hits)} hits / ${formatNumber(s.keyspace_misses)} misses`} tone="ok" />
        <Stat label="keys (DBSIZE)" value={formatNumber(s.dbsize)} />
        <Stat label="AOF" value={s.aof_enabled === '1' ? 'on' : 'off'} hint={`${formatNumber(s.rdb_changes_since_last_save)} changes since last RDB`} />
      </div>
      <div className="modules">
        <span className="muted">MODULE LIST</span>
        {d.modules.map((m) => <span key={m} className="chip chip-soft">{m}</span>)}
      </div>
      <h4>Lab keyspace ({formatNumber(d.labKeys)} keys via SCAN)</h4>
      <div className="table-wrap">
        <table>
          <thead><tr><th>feature</th><th>keys</th><th>type</th><th>sample key</th></tr></thead>
          <tbody>
            {d.keyspace.map((k) => (
              <tr key={k.feature}><td>{k.feature}</td><td>{formatNumber(k.keys)}</td><td><span className="chip chip-soft">{k.type}</span></td><td><code>{k.sample}</code></td></tr>
            ))}
            {d.keyspace.length === 0 && <tr><td colSpan="4" className="muted">No lab keys yet. Run some demos.</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  );
}
