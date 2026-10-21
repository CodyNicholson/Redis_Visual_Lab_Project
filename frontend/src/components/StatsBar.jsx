import React, { useEffect, useState } from 'react';
import { formatNumber, useRedisEvents } from './common.jsx';

/** Live INFO snapshot from Redis, refreshed every 3 seconds. */
export default function StatsBar() {
  const [stats, setStats] = useState(null);
  const [down, setDown] = useState(false);
  const { connected } = useRedisEvents();

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const response = await fetch('/api/lab/server/stats');
        if (!response.ok) throw new Error(String(response.status));
        const body = await response.json();
        if (!cancelled) {
          setStats(body);
          setDown(false);
        }
      } catch {
        if (!cancelled) setDown(true);
      }
    };
    load();
    const timer = setInterval(load, 3000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, []);

  if (down) return <div className="stats-bar"><span className="pill pill-bad">backend unreachable: is it still starting?</span></div>;
  if (!stats) return <div className="stats-bar"><span className="muted">connecting to Redis…</span></div>;
  const items = [
    ['Redis', stats.redis_version],
    ['memory', stats.used_memory_human],
    ['ops/sec', formatNumber(Number(stats.instantaneous_ops_per_sec))],
    ['hit ratio', stats.hit_ratio == null ? '–' : `${stats.hit_ratio}%`],
    ['keys', formatNumber(stats.dbsize)],
    ['clients', stats.connected_clients],
    ['expired', formatNumber(Number(stats.expired_keys))],
  ];
  return (
    <div className="stats-bar" aria-label="Live Redis statistics">
      {items.map(([label, value]) => (
        <span key={label} className="stats-item"><span className="stats-label">{label}</span><span className="stats-value">{value}</span></span>
      ))}
      <span className={`pill ${connected ? 'pill-ok' : 'pill-bad'}`} title="Server-Sent Events stream for pub/sub and expiry events">
        {connected ? '● live events' : '○ events offline'}
      </span>
    </div>
  );
}
