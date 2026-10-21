import React from 'react';
import { Empty, Stat, formatMs, useRedisEvents } from '../components/common.jsx';

export function PubSubVisual({ result }) {
  const { events, connected } = useRedisEvents();
  const messages = events.filter((e) => e.type === 'message');
  return (
    <div className="pubsub-visual">
      <div className="pubsub-feed">
        <h4>
          Live feed (PSUBSCRIBE lab:* → Server-Sent Events)
          <span className={`pill ${connected ? 'pill-ok' : 'pill-bad'}`}>{connected ? 'connected' : 'disconnected'}</span>
        </h4>
        {messages.length === 0 && <Empty>Publish a message: it arrives here through Redis, not from the HTTP response.</Empty>}
        <ul className="feed">
          {messages.slice(0, 12).map((m) => (
            <li key={m.id} className="slide-in">
              <span className="chip chip-soft">{m.channel}</span> {m.payload}
              <span className="muted"> {new Date(m.ts).toLocaleTimeString()}</span>
            </li>
          ))}
        </ul>
      </div>
      {result && (
        <div className="stat-row">
          <Stat label="subscribers that got it" value={result.data.receivers} tone="redis" hint="PUBLISH reply" />
          <Stat label="publish → listener" value={formatMs(result.data.roundTripMs)} tone="ok" />
          <Stat label="browser tabs listening" value={result.data.browsers} />
        </div>
      )}
    </div>
  );
}

export function StreamsVisual({ result }) {
  if (!result) return <Empty>Add some orders to the stream, then read them with a consumer group.</Empty>;
  const d = result.data;
  const delivered = new Set((d.delivered || []).map((e) => e.id));
  const claimed = new Set(d.claimed || []);
  const lastDelivered = d.lastDeliveredId;
  return (
    <div>
      <h4>
        <code>lab:stream:orders</code>: XLEN {d.length} · group <code>fulfillment</code> last-delivered-id <code>{lastDelivered}</code>
      </h4>
      <div className="stream-tape">
        {[...(d.recent || [])].reverse().map((entry) => {
          const isDelivered = compareIds(entry.id, lastDelivered) <= 0;
          return (
            <div key={entry.id}
              className={`stream-entry ${isDelivered ? 'entry-delivered' : 'entry-new'} ${delivered.has(entry.id) ? 'entry-just' : ''} ${claimed.has(entry.id) ? 'entry-claimed' : ''}`}>
              <code className="entry-id">{entry.id}</code>
              <div>{entry.fields.order}</div>
              <div className="muted">{entry.fields.qty} × {entry.fields.item}</div>
            </div>
          );
        })}
        {(d.recent || []).length === 0 && <span className="muted">stream is empty</span>}
      </div>
      <div className="legend">
        <span><i className="dot entry-new" /> not yet delivered</span>
        <span><i className="dot entry-delivered" /> delivered to the group</span>
        <span><i className="dot entry-just" /> delivered by this call</span>
      </div>
      <div className="stat-row">
        <Stat label="pending (read, not ACKed)" value={d.pendingTotal} tone={d.pendingTotal > 0 ? 'warn' : 'ok'} />
        {Object.entries(d.pendingPerConsumer || {}).map(([consumer, count]) => (
          <Stat key={consumer} label={`pending on ${consumer}`} value={count} />
        ))}
        {d.claimed && <Stat label="claimed by XAUTOCLAIM" value={d.claimed.length} tone="redis" />}
      </div>
    </div>
  );
}

/** Stream IDs are "<ms>-<seq>"; compare numerically, not as strings. */
export function compareIds(a, b) {
  const [ams, aseq] = String(a).split('-').map(Number);
  const [bms, bseq] = String(b).split('-').map(Number);
  return ams !== bms ? ams - bms : (aseq || 0) - (bseq || 0);
}
