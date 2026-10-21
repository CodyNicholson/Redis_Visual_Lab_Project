import React from 'react';
import { Bar, Empty, Stat, formatMs, formatNumber, useNow } from '../components/common.jsx';

export function RateLimitVisual({ result }) {
  const now = useNow(200);
  if (!result) return <Empty>Fire a burst: the first 5 requests in any 10-second window pass, the rest are rejected.</Empty>;
  const d = result.data;
  const requests = d.results || [{ ...d, request: 1 }];
  const windowMs = d.windowMs;
  const age = now - result.receivedAt;
  return (
    <div>
      <div className="requests">
        {requests.map((r, i) => (
          <div key={i} className={`request ${r.allowed ? 'request-ok' : 'request-blocked'}`} style={{ animationDelay: `${i * 90}ms` }}>
            <span className="request-n">#{r.request}</span>
            <span>{r.allowed ? '200' : '429'}</span>
          </div>
        ))}
      </div>
      <h4>Sliding window: timestamps in <code>lab:ratelimit:*</code> (sorted set)</h4>
      <div className="window-track">
        {(d.window || []).map((ts, i) => {
          const elapsed = Date.now() - ts;
          const pct = Math.min(100, (elapsed / windowMs) * 100);
          return <span key={i} className={`window-dot ${pct >= 100 ? 'window-out' : ''}`} style={{ left: `${100 - pct}%` }} title={`${(elapsed / 1000).toFixed(1)}s ago`} />;
        })}
        <span className="window-label window-label-left">−10 s</span>
        <span className="window-label window-label-right">now</span>
      </div>
      <div className="stat-row">
        {d.results ? (
          <>
            <Stat label="allowed" value={d.allowed} tone="ok" />
            <Stat label="blocked (HTTP 429)" value={d.blocked} tone="bad" />
          </>
        ) : (
          <>
            <Stat label="this request" value={d.allowed ? 'allowed' : 'blocked'} tone={d.allowed ? 'ok' : 'bad'} />
            <Stat label="remaining in window" value={d.remaining} />
          </>
        )}
        <Stat label="limit" value={`${d.limit} / ${windowMs / 1000}s`} hint={age < windowMs ? `window frees up in ~${Math.ceil((windowMs - age) / 1000)}s` : 'window has fully reset'} />
      </div>
    </div>
  );
}

export function LockVisual({ result }) {
  if (!result) return <Empty>Start the workers: each bar is the time one worker held the lock.</Empty>;
  const timeline = result.data.timeline || [];
  const end = Math.max(...timeline.map((t) => t.releasedMs || 0), 1);
  return (
    <div>
      <div className="gantt">
        {timeline.map((t, i) => (
          <div key={t.worker} className="gantt-row">
            <span className="gantt-label">{t.worker}</span>
            <span className="gantt-track">
              <span className="gantt-wait" style={{ width: `${(t.acquiredMs / end) * 100}%` }} />
              <span className="gantt-bar" style={{ left: `${(t.acquiredMs / end) * 100}%`, width: `${((t.releasedMs - t.acquiredMs) / end) * 100}%`, animationDelay: `${i * 160}ms` }}>
                🔒
              </span>
            </span>
            <span className="gantt-note">{t.attempts} tries</span>
          </div>
        ))}
      </div>
      <div className="gantt-axis"><span>0 ms</span><span>{end} ms</span></div>
      <div className="stat-row">
        <Stat label="max holders at once" value={result.data.maxConcurrentHolders} tone={result.data.maxConcurrentHolders === 1 ? 'ok' : 'bad'} />
        <Stat label="release with wrong token" value={result.data.wrongTokenRelease === 0 ? 'refused' : 'allowed!'} tone="ok" hint="Lua compare-and-delete" />
      </div>
    </div>
  );
}

export function TransactionsVisual({ result }) {
  if (!result) return <Empty />;
  const d = result.data;
  const total = d.alice + d.bob;
  return (
    <div className="tx-visual">
      <div className="accounts">
        {[['alice', d.alice], ['bob', d.bob]].map(([name, balance]) => (
          <div key={name} className="account">
            <div className="muted">lab:tx:balance:{name}</div>
            <div className="account-balance">${balance}</div>
            <div className="account-bar"><span style={{ width: `${(balance / Math.max(total, 1)) * 100}%` }} /></div>
          </div>
        ))}
        <Stat label="total" value={`$${total}`} hint={d.attempts ? '150 + 10 concurrent deposit' : 'money moves, never appears or vanishes'} />
      </div>
      {d.attempts && (
        <ol className="attempts">
          {d.attempts.map((a) => (
            <li key={a.attempt} className={a.committed ? 'attempt-ok' : 'attempt-bad'}>
              <b>Attempt {a.attempt}</b>: WATCH → GET → {a.interfered && <span className="interfere">another client changes alice → </span>}MULTI … EXEC →{' '}
              {a.committed ? <span className="ok">committed</span> : <span className="bad">nil (aborted)</span>}
            </li>
          ))}
        </ol>
      )}
      {d.execResults && <p className="muted">EXEC returned {JSON.stringify(d.execResults)}: both queued commands ran back to back.</p>}
    </div>
  );
}

export function LuaVisual({ result }) {
  if (!result) return <Empty>Start the sale: 100 buyers race for 20 items, once through a Lua script and once with GET + SET.</Empty>;
  const d = result.data;
  const max = Math.max(d.stock, d.naiveSold);
  return (
    <div>
      <h4>{d.buyers} buyers · {d.stock} items in stock</h4>
      <Bar label="Lua script (atomic)" value={d.atomicSold} max={max} tone="redis" text={`${d.atomicSold} sold`} marker={d.stock} markerLabel="stock" />
      <Bar label="GET + SET (racy)" value={d.naiveSold} max={max} tone={d.oversold > 0 ? 'bad' : 'warn'} text={`${d.naiveSold} sold`} marker={d.stock} markerLabel="stock" />
      <p className="muted">The white line marks the {d.stock} items that actually exist.</p>
      <div className="stat-row">
        <Stat label="oversold with GET + SET" value={d.oversold} tone={d.oversold > 0 ? 'bad' : 'ok'} hint={d.oversold > 0 ? 'items that do not exist were sold' : 'no overlap this time: try again'} />
        <Stat label="stock left (Lua)" value={d.atomicStockLeft} tone="ok" />
      </div>
      {d.winners?.length > 0 && <p className="muted">First orders: {d.winners.join(', ')}…</p>}
    </div>
  );
}

export function PipelineVisual({ result }) {
  if (!result) return <Empty />;
  const d = result.data;
  const max = Math.max(d.sequentialMs, d.pipelinedMs);
  return (
    <div>
      <div className="pipe-diagram" aria-hidden="true">
        <div className="pipe-lane">
          <span className="pipe-label">one at a time</span>
          {Array.from({ length: 6 }).map((_, i) => <span key={i} className="pipe-trip" style={{ animationDelay: `${i * 0.45}s` }} />)}
        </div>
        <div className="pipe-lane">
          <span className="pipe-label">pipelined</span>
          <span className="pipe-batch">
            {Array.from({ length: 6 }).map((_, i) => <span key={i} className="pipe-cmd" style={{ animationDelay: `${i * 0.05}s` }} />)}
          </span>
        </div>
      </div>
      <Bar label={`${formatNumber(d.commands)} × INCR, one at a time`} value={d.sequentialMs} max={max} tone="warn" text={formatMs(d.sequentialMs)} />
      <Bar label={`${formatNumber(d.commands)} × INCR, pipelined`} value={d.pipelinedMs} max={max} tone="redis" text={formatMs(d.pipelinedMs)} />
      <div className="stat-row">
        <Stat label="faster with pipelining" value={`${d.speedup}×`} tone="ok" />
        <Stat label="per command, one at a time" value={formatMs(d.sequentialMs / d.commands)} hint="mostly network round trip" />
      </div>
    </div>
  );
}
