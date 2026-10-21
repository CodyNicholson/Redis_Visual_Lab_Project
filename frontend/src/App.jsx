import React, { useCallback, useEffect, useState } from 'react';
import { CATEGORIES, DEMOS } from './demos/catalog.js';
import DemoView, { defaultInputs } from './components/DemoView.jsx';
import StatsBar from './components/StatsBar.jsx';
import TestRunner from './components/TestRunner.jsx';
import { RedisEventsProvider } from './components/common.jsx';

const SUITE = 'suite';

function readHash() {
  const id = window.location.hash.replace('#', '');
  return id === SUITE || DEMOS.some((d) => d.id === id) ? id : DEMOS[0].id;
}

export default function App() {
  const [selected, setSelected] = useState(readHash);
  const [states, setStates] = useState({});
  const [suiteResults, setSuiteResults] = useState({});

  useEffect(() => {
    const onHash = () => setSelected(readHash());
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  const open = (id) => {
    window.location.hash = id;
    setSelected(id);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };

  const demo = DEMOS.find((d) => d.id === selected);
  const state = (demo && states[demo.id]) || (demo ? { inputs: defaultInputs(demo), history: [] } : null);

  const update = useCallback((patch) => {
    setStates((prev) => {
      const current = prev[selected] || { inputs: defaultInputs(DEMOS.find((d) => d.id === selected)), history: [] };
      const next = typeof patch === 'function' ? patch(current) : patch;
      return { ...prev, [selected]: { ...current, ...next } };
    });
  }, [selected]);

  return (
    <RedisEventsProvider>
      <div className="app">
        <header className="topbar">
          <div className="brand">
            <span className="logo" aria-hidden="true"><span /><span /><span /></span>
            <div>
              <h1>Redis Visual Lab</h1>
              <p>Spring Boot · PostgreSQL · Redis 8: every button runs real commands</p>
            </div>
          </div>
          <StatsBar />
        </header>

        <div className="layout">
          <nav className="sidebar" aria-label="Demos">
            <button type="button" className={`nav-item nav-suite ${selected === SUITE ? 'nav-active' : ''}`} onClick={() => open(SUITE)}>
              ▶ Run all demos (self-test)
            </button>
            {CATEGORIES.map((category) => (
              <div key={category} className="nav-group">
                <div className="nav-heading">{category}</div>
                {DEMOS.filter((d) => d.category === category).map((d) => {
                  const status = suiteResults[d.id]?.status;
                  return (
                    <button key={d.id} type="button" className={`nav-item ${selected === d.id ? 'nav-active' : ''}`} onClick={() => open(d.id)}>
                      <span>{d.title}</span>
                      {status === 'pass' && <span className="ok" aria-label="passed">✓</span>}
                      {status === 'fail' && <span className="bad" aria-label="failed">✗</span>}
                    </button>
                  );
                })}
              </div>
            ))}
          </nav>

          <main className="main">
            {selected === SUITE
              ? <TestRunner results={suiteResults} setResults={setSuiteResults} onOpen={open} />
              : <DemoView key={demo.id} demo={demo} state={state} update={update} />}
          </main>
        </div>
      </div>
    </RedisEventsProvider>
  );
}
