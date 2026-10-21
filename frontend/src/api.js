// Calls the lab backend. Every lab endpoint returns a DemoResult
// ({ demo, commands, data, checks, elapsedMs }); the catalog endpoints return the raw
// JSON array with metadata in headers, which fetchCatalog turns into the same shape.

export function buildUrl(path, params = {}) {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') query.set(key, String(value));
  });
  const qs = query.toString();
  return qs ? `${path}?${qs}` : path;
}

export async function callApi(method, url) {
  const started = performance.now();
  const response = await fetch(url, { method });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(body.error || `${method} ${url} failed with ${response.status}`);
  }
  return { ...body, clientMs: Math.round(performance.now() - started) };
}

/** Parses "db;dur=12.3, serialize;dur=4.5" into { db: 12.3, serialize: 4.5 }. */
export function parseServerTiming(header) {
  const timings = {};
  (header || '').split(',').forEach((part) => {
    const [name, ...attrs] = part.trim().split(';');
    const dur = attrs.find((a) => a.trim().startsWith('dur='));
    if (name && dur) timings[name.trim()] = Number(dur.split('=')[1]);
  });
  return timings;
}

export async function fetchCatalog(source, size) {
  const started = performance.now();
  const response = await fetch(`/api/catalog/${source}?size=${size}`);
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.error || `Catalog request failed with ${response.status}`);
  }
  const rows = await response.json();
  const clientMs = Math.round(performance.now() - started);
  const timings = parseServerTiming(response.headers.get('Server-Timing'));
  const cache = response.headers.get('X-Cache');
  const bytes = Number(response.headers.get('X-Bytes'));
  const sha = response.headers.get('X-Sha256');
  const mb = (bytes / 1024 / 1024).toFixed(2);
  const key = `lab:catalog:${size}`;
  const sql = `SELECT … FROM products LEFT JOIN (SELECT product_id, count(*), avg(rating) FROM reviews GROUP BY product_id) … WHERE id <= ${size}`;
  const commands = source === 'db'
    ? [`SQL: ${sql}  (${timings.db} ms)`, `serialize ${rows.length} rows → ${mb} MB JSON  (${timings.serialize} ms)`]
    : cache === 'HIT'
      ? [`MGET ${key} ${key}:meta  → HIT, ${mb} MB  (${timings.redis} ms)`]
      : [
        `MGET ${key} ${key}:meta  → (nil), MISS  (${timings.redis} ms)`,
        `SQL: ${sql}  (${timings.db} ms)`,
        `serialize ${rows.length} rows → ${mb} MB JSON  (${timings.serialize} ms)`,
        `SET ${key} <${mb} MB> EX 600 ; SET ${key}:meta <sha256:rows> EX 600  (${timings.redisWrite} ms)`,
      ];
  return {
    demo: 'catalog',
    commands,
    checks: [
      { name: 'Received every row', passed: rows.length === Number(response.headers.get('X-Rows')), detail: `${rows.length} rows` },
    ],
    data: { source: response.headers.get('X-Source'), cache, rows: rows.length, bytes, sha, timings, clientMs, preview: rows.slice(0, 6), size },
    elapsedMs: timings.total,
    clientMs,
  };
}

/** Runs one demo button: a catalog fetch, or a DemoResult endpoint built from the action's path and params. */
export function runAction(action, inputs) {
  if (action.kind === 'catalog') return fetchCatalog(action.source, inputs.size);
  const path = typeof action.path === 'function' ? action.path(inputs) : action.path;
  const params = action.params ? action.params(inputs) : {};
  return callApi(action.method, buildUrl(path, params));
}
