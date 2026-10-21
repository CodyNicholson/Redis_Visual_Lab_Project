import { afterEach, describe, expect, it, vi } from 'vitest';
import { buildUrl, fetchCatalog, parseServerTiming, runAction } from '../api.js';

afterEach(() => vi.restoreAllMocks());

describe('api helpers', () => {
  it('builds query strings and skips empty values', () => {
    expect(buildUrl('/api/lab/search', { q: 'lamp', category: '', maxPrice: undefined })).toBe('/api/lab/search?q=lamp');
    expect(buildUrl('/api/lab/vectors', { color: '#e4572e' })).toBe('/api/lab/vectors?color=%23e4572e');
    expect(buildUrl('/api/lab/strings')).toBe('/api/lab/strings');
  });

  it('parses Server-Timing headers', () => {
    expect(parseServerTiming('db;dur=120.5, serialize;dur=8.25, total;dur=130')).toEqual({ db: 120.5, serialize: 8.25, total: 130 });
    expect(parseServerTiming(null)).toEqual({});
  });

  it('turns a catalog response into a demo result', async () => {
    const rows = Array.from({ length: 3 }, (_, i) => ({ id: i + 1, name: `p${i}`, price: 1 }));
    const headers = new Headers({
      'X-Source': 'redis', 'X-Cache': 'HIT', 'X-Rows': '3', 'X-Bytes': '2048', 'X-Sha256': 'abc',
      'Server-Timing': 'redis;dur=0.8, total;dur=1.1',
    });
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({ ok: true, headers, json: async () => rows });
    const result = await fetchCatalog('cache', 3);
    expect(globalThis.fetch).toHaveBeenCalledWith('/api/catalog/cache?size=3');
    expect(result.data).toMatchObject({ cache: 'HIT', rows: 3, bytes: 2048, sha: 'abc', size: 3 });
    expect(result.elapsedMs).toBe(1.1);
    expect(result.commands[0]).toContain('MGET lab:catalog:3');
    expect(result.checks[0].passed).toBe(true);
  });

  it('surfaces backend errors', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({ ok: false, status: 500, json: async () => ({ error: 'boom' }) });
    await expect(runAction({ method: 'POST', path: '/api/lab/strings' }, {})).rejects.toThrow('boom');
  });
});
