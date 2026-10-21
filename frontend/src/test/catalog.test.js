import { describe, expect, it } from 'vitest';
import { CATEGORIES, DEMOS, NODE_META, nodesFor } from '../demos/catalog.js';
import { VISUALS } from '../visuals/index.js';
import { defaultInputs } from '../components/DemoView.jsx';

const validSteps = (steps) => steps.every(([from, to, label]) => NODE_META[from] && NODE_META[to] && typeof label === 'string' && label.length > 0);

describe('demo catalog', () => {
  it('has unique ids', () => {
    expect(new Set(DEMOS.map((d) => d.id)).size).toBe(DEMOS.length);
  });

  it.each(DEMOS.map((d) => [d.id, d]))('%s is complete', (_, demo) => {
    expect(CATEGORIES).toContain(demo.category);
    expect(demo.description.length).toBeGreaterThan(0);
    expect(demo.actions.length).toBeGreaterThan(0);
    expect(VISUALS[demo.visual]).toBeTypeOf('function');
    expect(demo.test.path).toMatch(/^\/api\//);
    expect(validSteps(demo.flow)).toBe(true);
  });

  it.each(DEMOS.map((d) => [d.id, d]))('%s actions build valid flows and requests', (_, demo) => {
    const inputs = defaultInputs(demo);
    demo.actions.forEach((action) => {
      expect(action.label).toBeTruthy();
      if (action.flow) expect(validSteps(action.flow(inputs))).toBe(true);
      if (action.flowFor) expect(validSteps(action.flowFor({ data: {} }, inputs))).toBe(true);
      if (action.kind !== 'catalog') {
        const path = typeof action.path === 'function' ? action.path(inputs) : action.path;
        expect(path).toMatch(/^\/api\//);
        expect(['GET', 'POST', 'PUT', 'DELETE']).toContain(action.method);
      }
    });
  });

  it('every category has demos', () => {
    CATEGORIES.forEach((category) => expect(DEMOS.some((d) => d.category === category)).toBe(true));
  });

  it('orders flow participants left to right', () => {
    expect(nodesFor([['pg', 'api', 'x'], ['ui', 'redis', 'y']])).toEqual(['ui', 'api', 'redis', 'pg']);
  });
});
