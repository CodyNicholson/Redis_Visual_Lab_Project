import React from 'react';
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import FlowDiagram from '../components/FlowDiagram.jsx';
import ResultPanel from '../components/ResultPanel.jsx';
import { DEMOS } from '../demos/catalog.js';
import { VISUALS } from '../visuals/index.js';
import { compareIds } from '../visuals/MessagingVisuals.jsx';
import { flattenJson } from '../visuals/ModuleVisuals.jsx';

describe('FlowDiagram', () => {
  it('draws every participant and lists every step', () => {
    render(<FlowDiagram steps={[['ui', 'api', 'GET /x'], ['api', 'redis', 'GET key'], ['api', 'pg', 'SELECT 1']]} runKey={1} />);
    ['Browser', 'Spring Boot', 'Redis 8', 'PostgreSQL'].forEach((label) => expect(screen.getAllByText(label).length).toBeGreaterThan(0));
    expect(screen.getAllByText('SELECT 1').length).toBeGreaterThan(0);
    expect(screen.getAllByRole('listitem')).toHaveLength(3);
  });
});

describe('ResultPanel', () => {
  it('shows commands and check outcomes', () => {
    render(<ResultPanel actionLabel="Run" result={{
      demo: 'strings', elapsedMs: 1.5, clientMs: 4, commands: ['SET a 1'],
      checks: [{ name: 'worked', passed: true, detail: 'ok' }, { name: 'broke', passed: false, detail: 'no' }],
    }} />);
    expect(screen.getByText(/SET a 1/)).toBeInTheDocument();
    expect(screen.getByText('1/2 checks passed')).toBeInTheDocument();
    expect(screen.getByText('broke')).toBeInTheDocument();
  });
});

describe('visuals', () => {
  it.each(DEMOS.map((d) => [d.visual]))('%s renders before the first run', (name) => {
    const Visual = VISUALS[name];
    const { container } = render(<Visual result={null} history={[]} inputs={{}} run={() => {}} busy={null} />);
    expect(container.textContent.length).toBeGreaterThan(0);
  });

  it('renders a leaderboard result', () => {
    const Visual = VISUALS.leaderboard;
    render(<Visual result={{ data: { top: [{ rank: 1, player: 'Ada', score: 90 }, { rank: 2, player: 'Linus', score: 40 }], plays: [], adaRank: 1, over500: 0 } }} />);
    expect(screen.getAllByText('Ada').length).toBeGreaterThan(0);
    expect(screen.getByText('#2')).toBeInTheDocument();
  });
});

describe('helpers', () => {
  it('compares stream ids numerically', () => {
    expect(compareIds('1700000000000-10', '1700000000000-9')).toBeGreaterThan(0);
    expect(compareIds('999-0', '1000-0')).toBeLessThan(0);
  });

  it('flattens JSON documents by path', () => {
    expect(flattenJson({ a: 1, b: { c: [1, 2] } })).toEqual({ '$.a': '1', '$.b.c': '[1,2]' });
  });
});
