import React, { useMemo } from 'react';
import { Bar, Empty, Stat, formatMs, formatNumber, useNow, useRedisEvents } from '../components/common.jsx';

export function StringsVisual({ result }) {
  if (!result) return <Empty />;
  const d = result.data;
  const tiles = [
    ['lab:str:greeting', `"${d.greeting}"`, 'SET + APPEND'],
    ['GETRANGE 0 4', `"${d.slice}"`, 'substring'],
    ['MGET color size missing', JSON.stringify(d.mget), 'one round trip'],
    ['lab:str:once', `"${d.once}"`, `NX: first ${d.setnxFirst ? 'OK' : 'nil'}, second ${d.setnxSecond ? 'OK' : 'nil'}`],
    ['lab:str:price', String(d.price), 'INCRBYFLOAT 0.5'],
  ];
  return (
    <div className="tiles">
      {tiles.map(([key, value, note], i) => (
        <div key={key} className="tile pop-in" style={{ animationDelay: `${i * 80}ms` }}>
          <code className="tile-key">{key}</code>
          <div className="tile-value">{value}</div>
          <div className="muted">{note}</div>
        </div>
      ))}
    </div>
  );
}

export function CounterVisual({ result }) {
  if (!result) return <Empty>Start the race: every thread increments both counters at the same time.</Empty>;
  const d = result.data;
  return (
    <div>
      <h4>{d.threads} threads × {formatNumber(d.expected / d.threads)} increments = {formatNumber(d.expected)} expected</h4>
      <Bar label="INCR (atomic)" value={d.atomic} max={d.expected} tone="redis" text={`${formatNumber(d.atomic)} in ${formatMs(d.atomicMs)}`} />
      <Bar label="GET + SET (racy)" value={d.naive} max={d.expected} tone="warn" text={`${formatNumber(d.naive)} in ${formatMs(d.naiveMs)}`} />
      <div className="stat-row">
        <Stat label="lost with INCR" value={formatNumber(d.expected - d.atomic)} tone="ok" />
        <Stat label="lost with GET + SET" value={formatNumber(d.lostUpdates)} tone={d.lostUpdates > 0 ? 'bad' : 'ok'}
          hint={d.lostUpdates > 0 ? 'two threads read the same value, one write overwrote the other' : 'got lucky this time: run it again'} />
      </div>
    </div>
  );
}

export function TtlVisual({ result }) {
  const now = useNow(100);
  const { events } = useRedisEvents();
  if (!result) return <Empty>Create the keys and watch them count down. When Redis deletes one, its "expired" event arrives here live.</Empty>;
  const expiredEvents = events.filter((e) => e.type === 'expired' && e.payload.startsWith('lab:ttl:') && e.ts >= result.receivedAt - 1000);
  return (
    <div>
      <div className="tiles">
        {result.data.keys.map((k) => {
          const expiredEvent = expiredEvents.find((e) => e.payload === k.key);
          const left = k.ttlMs > 0 ? Math.max(0, k.ttlMs - (now - result.receivedAt)) : k.ttlMs;
          const total = k.key.endsWith('session') ? result.data.seconds * 2000 || k.ttlMs : result.data.seconds * 1000 || k.ttlMs;
          const pct = left > 0 && total > 0 ? left / total : 0;
          const gone = expiredEvent || (k.ttlMs === -2 || !k.exists);
          return (
            <div key={k.key} className={`tile ttl-tile ${gone ? 'ttl-gone' : ''} ${expiredEvent ? 'ttl-flash' : ''}`}>
              <svg viewBox="0 0 44 44" className="ring" aria-hidden="true">
                <circle cx="22" cy="22" r="18" className="ring-track" />
                {left > 0 && <circle cx="22" cy="22" r="18" className="ring-fill" style={{ strokeDashoffset: `${113 * (1 - pct)}` }} />}
                {k.ttlMs === -1 && <text x="22" y="27" textAnchor="middle" className="ring-text">∞</text>}
              </svg>
              <code className="tile-key">{k.key}</code>
              <div className="tile-value">{gone ? '(deleted)' : k.value}</div>
              <div className="muted">
                {expiredEvent ? '⚡ expired event received' : k.ttlMs === -1 ? 'PTTL -1 · persisted, no expiry' : left > 0 ? `PTTL ${(left / 1000).toFixed(1)}s` : 'waiting for expiry event…'}
              </div>
            </div>
          );
        })}
      </div>
      {expiredEvents.length > 0 && (
        <ul className="event-log">
          {expiredEvents.map((e) => <li key={e.id} className="slide-in"><code>{e.channel}</code> → <b>{e.payload}</b> <span className="muted">{new Date(e.ts).toLocaleTimeString()}</span></li>)}
        </ul>
      )}
    </div>
  );
}

export function HashesVisual({ result }) {
  const now = useNow(200);
  if (!result) return <Empty />;
  const { fields, csrfTtlMs, key } = result.data;
  const left = csrfTtlMs > 0 ? Math.max(0, csrfTtlMs - (now - result.receivedAt)) : csrfTtlMs;
  return (
    <div>
      <h4><code>{key}</code> · {Object.keys(fields).length} fields</h4>
      <table className="hash-table">
        <thead><tr><th>field</th><th>value</th><th>field TTL</th></tr></thead>
        <tbody>
          {Object.entries(fields).map(([field, value]) => (
            <tr key={field} className={field === 'csrf_token' && left === 0 ? 'row-fading' : ''}>
              <td><code>{field}</code></td>
              <td>{String(value)}</td>
              <td>{field === 'csrf_token' ? (left > 0 ? `${(left / 1000).toFixed(1)}s` : 'expired: press Read session') : <span className="muted">none</span>}</td>
            </tr>
          ))}
          {!('csrf_token' in fields) && (
            <tr className="row-gone"><td><code>csrf_token</code></td><td colSpan="2">removed by its field-level TTL (HPTTL → -2)</td></tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

export function ListsVisual({ result }) {
  if (!result) return <Empty>Enqueue some jobs, then process them. Watch them move from pending → processing → done.</Empty>;
  const { pending = [], processing = [], done = [], pendingCount, processed = [], pushed = [] } = result.data;
  const column = (title, items, tone, highlight = []) => (
    <div className={`queue-col queue-${tone}`}>
      <h4>{title} <span className="muted">({items.length})</span></h4>
      <div className="queue-items">
        {items.length === 0 && <span className="muted">empty</span>}
        {items.map((job) => <span key={job} className={`job ${highlight.includes(job) ? 'job-new' : ''}`}>{job}</span>)}
      </div>
    </div>
  );
  return (
    <div className="queues">
      {column(`lab:list:jobs: LLEN ${pendingCount}`, [...pending].reverse(), 'pending', pushed)}
      <div className="queue-arrow">LMOVE →</div>
      {column('lab:list:processing', processing, 'processing')}
      <div className="queue-arrow">LREM + LPUSH →</div>
      {column('lab:list:done (LTRIM 0 9)', done, 'done', processed)}
    </div>
  );
}

export function SetsVisual({ result }) {
  if (!result) return <Empty />;
  const d = result.data;
  const onlyA = d.onlyAlice || [];
  const onlyB = d.onlyBob || [];
  const both = d.intersection || [];
  return (
    <div className="sets-visual">
      <svg viewBox="0 0 420 220" className="venn" role="img" aria-label="Venn diagram of Alice's and Bob's skills">
        <circle cx="155" cy="110" r="95" className="venn-a" />
        <circle cx="265" cy="110" r="95" className="venn-b" />
        <text x="95" y="22" className="venn-title">alice</text>
        <text x="290" y="22" className="venn-title">bob</text>
        {onlyA.map((s, i) => <text key={s} x="105" y={70 + i * 24} textAnchor="middle" className="venn-item pop-in" style={{ animationDelay: `${i * 70}ms` }}>{s}</text>)}
        {both.map((s, i) => <text key={s} x="210" y={95 + i * 26} textAnchor="middle" className="venn-item venn-both pop-in" style={{ animationDelay: `${300 + i * 90}ms` }}>{s}</text>)}
        {onlyB.map((s, i) => <text key={s} x="315" y={70 + i * 24} textAnchor="middle" className="venn-item pop-in" style={{ animationDelay: `${i * 70}ms` }}>{s}</text>)}
      </svg>
      <div className="set-ops">
        <div><code>SINTER</code> {both.join(', ')}</div>
        <div><code>SUNION</code> {(d.union || []).length} skills</div>
        <div><code>SDIFF alice bob</code> {onlyA.join(', ')}</div>
        <Stat label={`unique visitors from ${formatNumber(d.pageViews)} page views`} value={formatNumber(d.uniqueVisitors)} tone="redis" hint="SADD ignores repeats · SCARD counts" />
      </div>
    </div>
  );
}

export function LeaderboardVisual({ result }) {
  const top = result?.data.top || [];
  const max = top.length ? top[0].score : 1;
  const lastPlays = useMemo(() => (result?.data.plays || []).slice(-6).reverse(), [result]);
  const ROW = 34;
  if (!result) return <Empty>Play some rounds: every ZINCRBY re-sorts the board.</Empty>;
  return (
    <div className="leaderboard-visual">
      <div className="board" style={{ height: Math.max(1, top.length) * ROW }}>
        {top.length === 0 && <span className="muted">Board is empty.</span>}
        {/* Rows are keyed by player and positioned by rank, so a rank change animates as a slide */}
        {[...top].sort((a, b) => a.player.localeCompare(b.player)).map((row) => (
          <div key={row.player} className="board-row" style={{ transform: `translateY(${(row.rank - 1) * ROW}px)` }}>
            <span className="board-rank">#{row.rank}</span>
            <span className="board-name">{row.player}</span>
            <span className="board-track"><span className="board-fill" style={{ width: `${(row.score / max) * 100}%` }} /></span>
            <span className="board-score">{formatNumber(row.score)}</span>
          </div>
        ))}
      </div>
      <div className="board-side">
        {lastPlays.length > 0 && (
          <>
            <h4>Last ZINCRBYs</h4>
            <ul className="event-log">
              {lastPlays.map((p, i) => <li key={i}><b>{p.player}</b> +{p.points} → {formatNumber(p.score)}</li>)}
            </ul>
          </>
        )}
        <div className="stat-row">
          <Stat label="Ada's rank (ZREVRANK)" value={result.data.adaRank ? `#${result.data.adaRank}` : 'unranked'} />
          <Stat label="players ≥ 500 (ZCOUNT)" value={result.data.over500} />
        </div>
      </div>
    </div>
  );
}
