# Redis Visual Lab design direction

Written in the design pass (step 5b of the visual-lab skill). Every lab has its own art direction.

## Subject and audience
- Redis: an in-memory data store, here Redis 8 next to a Spring Boot API and PostgreSQL. Its
  world is RAM: keys, TTL countdowns, hot paths, sub-millisecond answers. The brand speaks in
  Redis red on "midnight" (#091A23) with a sky blue (#80DBFF) for data.
- Visitors are backend developers who know a database and want to see what Redis does for them.
- The UI's job is to make "fast" and "hot" visible: what lives in memory, what expires, what is
  still on disk.

## Candidates considered
- `ui-ux-pro-max` (`"Redis in-memory data store cache developer learning lab" --design-system`)
  suggested "Swiss Modernism 2.0": navy #1E3A5F, gold #A16207, EB Garamond.
- Rejected: the same academic result came back for every tool queried; a library serif fights a
  tool whose whole point is speed.
- Kept: the strict grid, 4.5:1 contrast, visible focus, reduced motion.
- Dark was chosen from the use scene (a terminal-heavy, live-stats lab next to `redis-cli`) and
  from Redis' own midnight brand colour, not by category.

## Direction (one sentence)
"In-memory heat": a Redis-midnight ground ruled like a memory bank, a Redis-red header band
carrying the live server stats, red for writes and hot keys, and sky blue for the slow store
(Postgres) and for reads.

## Tokens
| Role | Value | Notes |
|---|---|---|
| Background `--bg` | #0b1f29 | Redis midnight; `--grid-line` #10283a: a row every 28px, a bank edge every 112px |
| Panels `--panel`, `--panel-2` | #112a37, #163341 | Redis "dusk"; `--border` #26495b |
| Text `--text`, `--muted` | #eaf2f5, #8fa6b2 | muted 6.7:1 on `--bg`, 5.2:1 on `--panel-2` |
| Accent `--accent` | #ff4438 | Redis red; midnight ink on it (5.2:1); red text uses `--accent-text` #ff8a80 |
| Header band | #ff4438 with #091a23 ink | the one bold element; secondary ink #3a0d08 (4.9:1) |
| Node roles | redis = accent, pg/store #80dbff, api #f4d35e, threads #ff9f5a, ui #5fd38d | red is Redis, sky is Postgres, yellow the Spring backend, orange its virtual threads |
| Status ok / bad / warn | #5fd38d / #ff7a7a / #ffb84d | always with ✓ / ✗ / text; on the red band pills switch to ink |

## Type
- Display: Unbounded Variable (`@fontsource-variable/unbounded@5.3.0`), weight 640–700,
  tracking -0.02 to -0.03em, for h1 and h2 only. Wide, rounded and fast: it carries the "speed"
  idea in the wordmark and demo titles.
- Body: Figtree Variable (`@fontsource-variable/figtree@5.3.0`), 1rem / 1.6.
- Mono: Geist Mono Variable (`@fontsource-variable/geist-mono@5.3.0`) for commands, keys and
  the live stats.
- Scale: h2 1.75rem (1.4rem on phones), h3 1.05rem, body 1rem, labels 0.8rem. Tabular figures on
  every live number.

## Background and surfaces
- `--page-bg`: two hairline gradients on midnight, a memory bank's rows and bank boundaries,
  fixed while the page scrolls.
- Radii 6–12px (the Redis mark's soft corners). Depth from surface steps (midnight, dusk,
  dusk-light), not shadows.
- Code panes go darker than the ground (#071319) so commands read as a terminal.

## Motion personality
- Kept the CSS keyframes that answer a run or a live event: the TTL rings counting down, the
  leaderboard re-sorting, pipeline commands batching, the rate-limit window sliding.
- No GSAP: the visuals are already event-driven and short.
- `prefers-reduced-motion` stops every animation and transition (global rule, unchanged).

## Generic defaults avoided
- Navy #0b1120 page with a red radial glow, Inter and JetBrains Mono (the old starter look).
- "Near-black with one bright accent": the ground is Redis' blue-green midnight, and the palette
  is a two-signal system (red hot and writes, sky reads and the slow store), not one accent.
- All-caps tracked sidebar labels: sentence case on a rule.
- The three-bar logo on red would vanish on the red band and read as a menu button: it is now
  the favicon's stacked layers in red on a midnight tile.

## Audit log
- 2026-11-21 `impeccable detect` (regex scan of `src`): 0 findings.
  - Craft floor: themed `::selection` and scrollbars from the palette; live numbers tabular.
  - Not changed (shared older template): the category chip above each demo title and the ▶
    glyph on "Run all demos".
- 2026-11-21 `web-design-guidelines` on `src/**/*.{jsx,css}`: no `transition: all`, no clickable
  divs, every control labelled, flow captions are `aria-live`. The geo map's click-to-search is
  a pointer shortcut; the "Search radius" button is the keyboard path. Added `theme-color`,
  `:focus-visible` rings (sky, so focus never reads as an error), `text-wrap` on headings,
  `scroll-padding-top` and `touch-action: manipulation`.
  - Not changed: no skip link (a `#main` link would collide with the hash routes), headings
    stay sentence case by design.
- 2026-11-21 `screenshot.sh`: the stock script never finishes on this lab, because it waits for
  network idle and the stats bar holds an SSE stream open. Ran a copy that waits for `load`
  instead. First run: 20 screenshots, 1 problem: the self-test table overflowed a 390px phone by
  126px. Wrapped it in `.table-wrap`. Looking at the shots: the green "live events" pill was
  unreadable on the red band; pills on the band now use midnight ink.
- 2026-11-21 `screenshot.sh` (load-wait copy), after the fixes: 20 screenshots (10 routes at 1440
  and 390), 0 problems. `test-isolated.sh`: security and design checks pass; 25 of 25 demo
  self-tests pass through nginx; `GET /api/health` fails because this older backend has no
  health route.
