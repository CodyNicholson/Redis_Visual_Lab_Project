import React from 'react';
import { Bar, Empty, Stat, formatBytes, formatNumber } from '../components/common.jsx';

export function HyperLogLogVisual({ result }) {
  if (!result) return <Empty />;
  const d = result.data;
  return (
    <div>
      <div className="stat-row">
        <Stat label="PFCOUNT (estimate)" value={formatNumber(d.estimate)} tone="redis" />
        <Stat label="SCARD (exact)" value={formatNumber(d.exact)} />
        <Stat label="error" value={`${d.errorPct}%`} tone={d.errorPct < 2 ? 'ok' : 'bad'} hint="standard error ≈ 0.81%" />
        <Stat label="memory saved" value={`${Math.round(d.setBytes / d.hllBytes)}×`} tone="ok" />
      </div>
      <h4>Memory for {formatNumber(d.visits)} visits</h4>
      <Bar label="HyperLogLog" value={d.hllBytes} max={d.setBytes} tone="redis" text={formatBytes(d.hllBytes)} />
      <Bar label="Exact SET" value={d.setBytes} max={d.setBytes} tone="warn" text={formatBytes(d.setBytes)} />
    </div>
  );
}

export function BitmapsVisual({ result }) {
  if (!result) return <Empty />;
  const d = result.data;
  const allWeek = d.grid[0].map((_, u) => d.grid.every((row) => row[u] === 1));
  return (
    <div>
      <div className="bitmap">
        {d.grid.map((row, day) => (
          <div key={day} className="bitmap-row">
            <span className="bitmap-day">{d.days[day]}</span>
            <span className="bitmap-cells">
              {row.map((bit, u) => (
                <span key={u} className={`bit ${bit ? 'bit-on' : ''} ${allWeek[u] ? 'bit-all' : ''}`}
                  style={{ animationDelay: `${(day * 64 + u) * 3}ms` }} title={`user ${u}: ${bit}`} />
              ))}
            </span>
            <span className="bitmap-count">{formatNumber(d.dailyCounts[day])}</span>
          </div>
        ))}
      </div>
      <p className="muted">First 64 of {formatNumber(d.users)} users. Gold cells are users active all 7 days (BITOP AND).</p>
      <div className="stat-row">
        <Stat label="active every day (AND)" value={formatNumber(d.allWeek)} tone="warn" />
        <Stat label="active any day (OR)" value={formatNumber(d.anyDay)} tone="redis" />
        <Stat label="memory per day" value={formatBytes(d.bytesPerDay)} hint={`${formatNumber(d.users)} users → ${Math.ceil(d.users / 8)} bytes of bits`} />
      </div>
    </div>
  );
}

export function BloomVisual({ result }) {
  if (!result) return <Empty />;
  const d = result.data;
  if (result.demo === 'bloom-check') {
    return (
      <div className={`bloom-answer ${d.maybeTaken ? 'bloom-maybe' : 'bloom-no'}`}>
        <div className="bloom-name">"{d.name}"</div>
        <div className="bloom-verdict">{d.maybeTaken ? 'probably taken' : 'definitely available'}</div>
        <div className="muted">
          {d.maybeTaken ? 'Every hashed bit was set. Confirm with the database before rejecting.' : 'At least one hashed bit was 0, so it was never added. No database lookup needed.'}
        </div>
      </div>
    );
  }
  return (
    <div>
      <div className="stat-row">
        <Stat label="false negatives" value="0" tone="ok" hint={`${formatNumber(d.probes)} added names all found`} />
        <Stat label="false positives" value={`${d.falsePositives} / ${formatNumber(d.probes)}`} tone="warn" hint={`${d.falsePositiveRatePct.toFixed(1)}% (target 1%)`} />
      </div>
      <h4>Memory for {formatNumber(d.inserted)} usernames</h4>
      <Bar label="Bloom filter" value={d.bloomBytes} max={d.setBytes} tone="redis" text={formatBytes(d.bloomBytes)} />
      <Bar label="SET of the names" value={d.setBytes} max={d.setBytes} tone="warn" text={formatBytes(d.setBytes)} />
    </div>
  );
}

const MAP = { w: 600, h: 330, lonMin: -125, lonMax: -66, latMin: 24, latMax: 50 };
const project = (lon, lat) => [
  ((lon - MAP.lonMin) / (MAP.lonMax - MAP.lonMin)) * MAP.w,
  ((MAP.latMax - lat) / (MAP.latMax - MAP.latMin)) * MAP.h,
];

export function GeoVisual({ result, run, busy }) {
  const center = result?.data.center || { lon: -87.6298, lat: 41.8781 };
  const radiusKm = result?.data.radiusKm || 800;
  const nearby = new Map((result?.data.nearby || []).map((n, i) => [n.name, { ...n, rank: i + 1 }]));
  const [cx, cy] = project(center.lon, center.lat);
  const rx = (radiusKm / (111.32 * Math.cos((center.lat * Math.PI) / 180)) / (MAP.lonMax - MAP.lonMin)) * MAP.w;
  const ry = (radiusKm / 110.57 / (MAP.latMax - MAP.latMin)) * MAP.h;

  const onClick = (e) => {
    if (busy) return;
    const box = e.currentTarget.getBoundingClientRect();
    const x = ((e.clientX - box.left) / box.width) * MAP.w;
    const y = ((e.clientY - box.top) / box.height) * MAP.h;
    const lon = MAP.lonMin + (x / MAP.w) * (MAP.lonMax - MAP.lonMin);
    const lat = MAP.latMax - (y / MAP.h) * (MAP.latMax - MAP.latMin);
    run('search', { lon: lon.toFixed(4), lat: lat.toFixed(4) });
  };

  return (
    <div className="geo-visual">
      <svg viewBox={`0 0 ${MAP.w} ${MAP.h}`} className="geo-map" onClick={onClick} role="img" aria-label="Map of stores; click to search">
        {Array.from({ length: 12 }).map((_, i) => <line key={`v${i}`} x1={i * 50} y1="0" x2={i * 50} y2={MAP.h} className="grid-line" />)}
        {Array.from({ length: 7 }).map((_, i) => <line key={`h${i}`} x1="0" y1={i * 50} x2={MAP.w} y2={i * 50} className="grid-line" />)}
        {result && <ellipse key={`${cx}-${cy}-${radiusKm}`} cx={cx} cy={cy} rx={rx} ry={ry} className="geo-radius" />}
        {(result?.data.stores || []).map((s) => {
          const [x, y] = project(s.lon, s.lat);
          const hit = nearby.get(s.name);
          return (
            <g key={s.name} className={hit ? 'geo-hit' : 'geo-store'} style={hit ? { animationDelay: `${hit.rank * 80}ms` } : undefined}>
              <circle cx={x} cy={y} r={hit ? 6 : 3.5} />
              {hit && <text x={x + 8} y={y + 4}>{hit.rank}. {s.name}</text>}
            </g>
          );
        })}
        <g className="geo-center"><circle cx={cx} cy={cy} r="5" /><circle cx={cx} cy={cy} r="12" className="geo-ping" /></g>
      </svg>
      <div className="geo-side">
        {!result && <Empty>Click anywhere on the map (or press Search radius) to find the nearest stores.</Empty>}
        {result && (
          <>
            <h4>GEOSEARCH within {radiusKm} km</h4>
            <ol className="geo-list">
              {result.data.nearby.map((n) => <li key={n.name}>{n.name} <span className="muted">{formatNumber(n.distanceKm)} km</span></li>)}
              {result.data.nearby.length === 0 && <li className="muted">No stores in range. Try a bigger radius.</li>}
            </ol>
            <p className="muted">GEODIST New York ↔ Los Angeles: {formatNumber(result.data.newYorkToLosAngelesKm)} km</p>
          </>
        )}
      </div>
    </div>
  );
}
