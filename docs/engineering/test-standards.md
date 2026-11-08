# Test standards — CineFilm

Adapted from goldenhour's `docs/engineering/test-improvement-standards.md` and `frontend-test-standards.md` and from
CineLight's `test-standards.md` (2026-11-08). Read the section for the side you are working on before you write or
change a test.

- **Backend:** its section arrives with phase 2 of `ci-test-deploy-plan.md`.
- **Frontend:** below.

## Philosophy (both sides)

Tests exist to protect behaviour, not to hit a coverage number. A test that passes when the feature is broken is
worse than no test: it creates false confidence. The coverage gates are a floor that catches untested code, not
proof that the tests are good.

Three questions every test must answer:

1. **What specific behaviour is being tested?** Not "the scrubber works" but "Page Down at 20 s goes to 0, not to
   −40 s".
2. **What exact inputs produce what exact outputs?** Specific values. A threshold at 92% gets tests at one
   millisecond below, exactly at, and above.
3. **What breaks if this test fails?** If you cannot answer, the test protects nothing. If you cannot imagine a code
   change that would break it, delete it.

**The handover is the spec.** Tests assert what `docs/design/handover/README.md` promises: copy, aria roles and
states (§7), keyboard behaviour, the numbered decisions. They do not assert class names, hook call counts or
component structure.

---

# Frontend

Vitest 4 + React Testing Library + `@testing-library/user-event` + jsdom, plain JavaScript (no PropTypes, no
Tailwind: both are deliberate decisions). Tests live next to the code (`Thing.jsx` → `Thing.test.jsx`); shared
helpers and fixtures live in `src/test/`.

```bash
cd frontend
npm test                                  # plain run, no coverage
npx vitest run src/screens/Player.test.jsx
npm run test:coverage                     # what CI runs: per-file floor + global floor
```

## CineFilm's conventions (follow them; do not start a second style)

**Mock at the fetch boundary.** `src/test/mockApi.jsx` provides `installFetch(route)`, `respond(data, status)` and
`renderApp(path)`. `installFetch` stubs `globalThis.fetch` with contract-shaped defaults for every endpoint; a test
overrides only what it is about:

```jsx
installFetch((method, url) => (url === '/api/home' ? respond({ ...EMPTY, hero }) : null));
renderApp('/');
```

Returning `null` from the route function falls through to the default response. Always `vi.unstubAllGlobals()` in
`afterEach`. Do **not** `vi.mock` anything under `src/api`, and do not add MSW: the API client (`X-CineFilm`
header, 401 handling, 204 handling) is part of what every screen test exercises.

**Fixtures are shaped like the API contract** (`src/test/fixtures/*.json`, named for the shape: `home.json`,
`film-heat.json`, `films-empty.json`). To vary one, `structuredClone` it and change the field the test is about;
do not hand-write a second whole payload.

**`userEvent` is the default** for everything a person does: click, type, tab, keyboard, select, hover. Use
`userEvent.setup()` when one test needs state across calls (a held mouse button, a drag). `fireEvent` is for what
`userEvent` cannot produce: media element events (`loadedMetadata`, `timeUpdate`, `waiting`, `ended`), `change` on
a range input, and synthetic `keyDown` on a frozen fake clock.

**Prefer `findBy*` to `waitFor` + `getBy*`.** Same wait, better failure message. Wait on something the fetch
actually gates: an element that is on screen from the first paint proves nothing.

## Queries

Where an element has a role contract, assert through it, because the role, the accessible name and the state *are*
the behaviour for anyone not using a mouse:

```jsx
// Right: the contract a screen reader and a keyboard user depend on
const sort = screen.getByRole('group', { name: 'Sort' });
expect(within(sort).getByRole('button', { name: 'Title' })).toHaveAttribute('aria-pressed', 'true');

// Wrong: passes even if the control has no accessible name
fireEvent.click(container.querySelector('.segmented__btn'));
```

- **Role queries are required** for anything interactive (`button`, `link`, `switch`, `radio`, `slider`,
  `searchbox`, `combobox`, `menuitemradio`), dialogs, regions and headings.
- **Never `container.querySelector`** (and no `parentElement`/`firstChild` walking, no class-name queries). If
  nothing can be queried, the component is missing a role or a test-id: add one.
- **`data-testid` is for the decorative.** `aria-hidden` elements have no role by design, so they carry a test-id
  when a test must reach them: `art`, `skeleton`, `skeleton-row`, `continue-fill`, `scrubber-buffered`,
  `player-video`. Everything with a role is found by role.
- `alt=""` images are presentational and have no role; `getByAltText('')` is the way to reach one.

## Assertions

- **Assert the value, not that a value exists.** `toBeInTheDocument()` survives a change that renders the wrong
  verdict; `toHaveTextContent('1h 45m left')` does not.
- **Assert the negative too.** Most of the handover's rules are about what must *not* appear: no Resume for a
  watched film, no See all on Continue watching, no subtitles picker when there are none. Use `queryBy*` +
  `not.toBeInTheDocument()`.
- No `toBeTruthy()` on a query result (`getBy*` already throws; on `queryBy*` it passes for the wrong element).
- No snapshots: nobody reads the diff and they fail on every unrelated change.
- Exact copy is part of the spec: assert the handover's strings ("The house lights are up", "Signed out", "The
  projector jammed", "Nothing on the shelf"). Copy that changes needs a note in the PR (AGENTS.md rule 1).

## What "boundary" means in a UI

The backend rule (every threshold at the boundary, one below, one above) transfers directly:

- **The 92% / credits-marker end card** (`resume.js`): 9,199 / 9,200 / 9,201 ms of 10,000.
- **Resume rewind** (10 s, never below 0), **auto-hide** (3 s: 2,999 / 3,000), **toast** (4 s).
- **Counts:** zero, one and many ("1 film" / "2 films"; "0 of 1 films in your library").
- **Absence:** `null`, `undefined`, `''`, `[]` and a field missing from the payload are five different inputs.
  Legacy and partial payloads make the last one real (`preferences` missing, `directors` missing, no `file`).
- **Clamping:** seek before 0 and past the end, volume at 0 and 1, scrubber drag past either end of the track.
- jsdom has no layout. A rule that only exists in a media query cannot be asserted by rendering: assert the class or
  attribute the component sets, and leave pixel behaviour to the browser check. For pointer maths, stub
  `getBoundingClientRect` (see `Scrubber.test.jsx`).

## Degrade paths get their own tests

These are the rules a refactor quietly drops. Each is a named test, not a branch inside a happy-path test:

- a fetch that **fails** shows "The projector jammed" and **Try again** really refetches (every screen)
- a **401** shows the sign-in card over the same route and playback pauses; signing in resumes only what was
  playing; a wrong password keeps the card open with the handover error
- a failed **preference save** or a failed **mark-watched** changes nothing the user can see (and mark-watched
  rolls its optimistic override back)
- a film with **no subtitles** shows the inert state and `C` does nothing
- **missing artwork** falls back to the colour block; a missing part of a collection is the dashed tile
- an **empty library** shows the empty state, **filters that match nothing** show "Nothing on the shelf"

Write the test so its name states the rule: `survivesAFailedPreferenceSave` is a rule; `handlesError` is not.

## Accessibility is assertions, not a manual pass (handover §7, AGENTS.md rule 2)

Every interactive element added or changed gets, at minimum:

- an accessible name asserted through `getByRole(role, { name })`
- its state asserted: `aria-pressed` (segmented controls), `aria-checked` (`menuitemradio`, `switch`),
  `aria-expanded` + `aria-haspopup` (player menu buttons), `aria-current="page"` (navigation), `disabled`
- keyboard operation where it is not a native `<button>`: the scrubber is a `role="slider"` with a words
  `aria-valuetext` and Home / End / Page keys; menus move focus with the arrow keys and wrap
- status messages are `role="status"` (buffering, toasts, the library count, loading skeletons), errors
  `role="alert"`, the sign-in card `role="dialog"` + `aria-modal`
- focus is where the handover says: the player on open, the checked menu item when a menu opens, **Mark as
  watched** on the end card, the password field on the sign-in card, the player again after a menu closes

Touch-target size and the focus ring are CSS; they are checked in the browser (and by the axe scan in phase 7), not
in jsdom.

## Keyboard tests: send the key where the browser would

`userEvent.keyboard` already dispatches to `document.activeElement`, which is what a browser does. Keep it that way:

- **Never `fireEvent.keyDown(someNode, …)` when focus is elsewhere.** `fireEvent.click` does not move focus in
  jsdom, so when one panel replaces another and the pressed control is destroyed, focus falls to `<body>`, Escape is
  dead in the shipped product, and a test aimed at the panel node still passes.
- After a transition that removes the focused control, assert `expect(thing).toHaveFocus()` *before* the next key.
  (`Player.test.jsx`: after choosing a quality, the player has focus again.)
- The single exception is a frozen fake clock, where `userEvent` would hang: `fireEvent.keyDown(document.activeElement, …)`.

## Async, timers and the traps this codebase has already met

- **A commit is not its effects.** `findBy*` resolves on the commit that renders what it waited for, but outside
  `act` (a resolved request, a timer) React leaves that commit's `useEffect`s to a later task. Anything an effect
  does (arm a timer, subscribe, start a fetch) is only guaranteed after settling inside an awaited `act`.
- **Never put the click inside the wait.** `waitFor` re-runs its callback every ~50 ms; a click in there re-fires
  on every poll. Await the element, click once, then assert.
- **Fake timers are installed in the test that needs them, restored in `afterEach`** (`vi.useRealTimers()` on a
  test's last line is skipped by the first failing assertion). Timers armed on the real clock before
  `vi.useFakeTimers()` are not controlled by it: install the fake clock *before* the action that arms the timer.
  Fake only what you need (`toFake: ['setTimeout', 'clearTimeout']`).
- **No sleeps.** There is no `setTimeout` or `await new Promise(r => setTimeout(...))` in any test. Wait for the UI
  (`findBy*`, `vi.waitFor`) or drive a fake clock.
- **No never-settling promise in an async *action*.** React 19 entangles async actions process-wide, so one
  `new Promise(() => {})` handed to a form action/`useActionState` poisons every later submit in the file. (A
  never-settling `fetch` to hold a screen in its Loading state is fine: that is an effect, not an action.) Hand
  the test the resolver and settle it before the test ends.
- **A timer the code arms must not outlive the test.** Release what a test opens in `afterEach`, through the
  handle the code gives for it.
- **Do not leak mock implementations between tests.** `installFetch` stubs the global and every file does
  `vi.unstubAllGlobals()` in `afterEach`; do not depend on a route another test installed.
- **Dates and time zones.** A test that formats a date or says "today" pins the instant (`vi.setSystemTime` with
  `toFake: ['Date']`) to one that is not the day the suite runs, and pins `process.env.TZ` at the top of the file
  if the output depends on the zone. The date shown on the detail page ("added March 2024") is a fixed instant in
  the fixture, so it is safe.

## Media (the player)

`src/test/setup.js` gives jsdom a working `play()`/`pause()` (they flip `paused` and fire `play`/`pause`) and
`load()`. Everything else about a `<video>` is driven by the test, because jsdom has no media pipeline:

- reach the element with `screen.getByTestId('player-video')`, then dispatch `fireEvent.loadedMetadata(video)`,
  `timeUpdate`, `waiting`, `playing`, `ended`, `progress`
- `video.currentTime` is settable; `duration` and `buffered` are overridden with `Object.defineProperty`
- fullscreen: set `document.fullscreenElement` / `document.exitFullscreen` and
  `HTMLElement.prototype.requestFullscreen`, and delete them again in `afterEach`

## Structure

- **One behaviour per test.** If the name needs "and", split it (a few tests walk one short flow when the steps
  are the behaviour; say so in the name).
- **`describe` names the surface; the test name states the rule.** Read together they should be a sentence a
  non-author can check against the handover.
- **Comment the why, never the what:** the bug a case prevents, the rule it pins, the handover decision it comes
  from.
- **Fixtures near the top, shared, named for their shape.** A fixture named `data` is not reusable.
- **Do not test a hook through a bespoke harness component when a real consumer will do.** Hooks that only make
  sense alone (`useResource`, `useDebouncedValue`, `usePlayback`) use `renderHook`; hooks that serve one screen
  (`useSlashFocus` → Layout) are tested through the page that uses them, with a small host only when no single
  consumer exists, and a comment saying so.
- **Layering is tested too.** `src/api` and `src/state` contain no JSX; their tests use `renderHook` and the fetch
  stub, never a component.

## Coverage gates

`npm run test:coverage` is `vitest run --coverage && node scripts/check-coverage-floor.mjs`. Two layers, exactly
goldenhour's numbers:

1. **Per file: 95% lines and 90% branches for every file in `src/`** (`test.coverage.thresholds` with
   `perFile: true` in `frontend/vite.config.js`; the run fails naming the file). `include` is
   `src/**/*.{js,jsx}`, so a file no test imports counts as 0% and fails the gate: a new file arrives with its
   tests.
2. **A global floor on the whole run**, measured on 2026-11-08 and rounded down to a whole percent: **lines 99,
   statements 99, functions 98, branches 97** (measured 99.64 / 99.01 / 98.46 / 97.98).
   It lives in `frontend/scripts/check-coverage-floor.mjs`, reading `coverage/coverage-summary.json`, because
   Vitest 4 applies `perFile` to every threshold at once (goldenhour's Vitest 5 can scope it to a glob). Move the
   floor back into the config if CineFilm adopts Vitest 5.

**Exclusions** (in `vite.config.js`, each justified there). Nothing in `src/` is excluded from checking
altogether except what is not product code:

| Excluded | Why |
|---|---|
| `src/test/**` | test setup, fixtures and helpers |
| `src/**/*.test.{js,jsx}` | the tests themselves |
| `src/main.jsx` | bootstrap that mounts `<App/>` into the page; nothing to unit-test |

There are no per-file threshold overrides today: every file clears 95/90 on its own (lowest branches:
`Collection.jsx` 92.9, `Player.jsx` 93.5). An override is added only for a branch that truly cannot be reached,
written as its own glob entry at the measured figure with the reason beside it, never as an exclusion. A guard that
protects real behaviour (a double submit, a race, a stale response) is never an exception: it gets a test. Do not
add a file to an exclusion list to dodge writing a test.

**Ratchet rule: raise the numbers when coverage rises; never lower them without the owner's say-so.** Never lower a
per-file floor to fit a file; write the test.

**Changed-lines check (arrives in phase 4).** Locally, after `npm run test:coverage`: `pip install
diff-cover==10.0.0` then, from the repo root, `sed 's#^SF:#SF:frontend/#' frontend/coverage/lcov.info >
/tmp/lcov-root.info && diff-cover /tmp/lcov-root.info --compare-branch=origin/main --fail-under=90`. The `SF:`
rewrite is required: lcov paths are relative to `frontend/`, and without it nothing matches and the check passes
vacuously. Output goes to the ignored `frontend/coverage/`.

## Mutation-checking a test without destroying work

Mutating production code to prove a test can fail is encouraged. Restoring it is where work gets lost.

- **Never restore with `git checkout -- <path>`, `git restore` or `git stash`.** They revert to HEAD and silently
  delete any uncommitted change in the file, usually the very change under test.
- Back up with `cp` and restore with `cp`, or commit first, or mutate in a separate worktree.
- Confirm the restore: `git diff` on the file shows exactly the change you started with. An unrestored mutation
  looks like a flake.

## What not to do

- Do not add tests that assert a constructor set a field, or that something rendered with no claim about *what*.
- Do not use `container.querySelector`, class-name queries or `toBeTruthy()`.
- Do not mock the component under test's own children to make a test pass; if it needs that, the test is at the
  wrong level.
- Do not `vi.mock` the API modules; stub `fetch`.
- Do not skip or disable a test. Fix it or delete it.
- Do not snapshot a tree.
- Do not assert inside a raw `requestAnimationFrame` or `setTimeout` callback and settle a promise after it: a
  throw there never reaches the `resolve()`, and the test hangs to the timeout with the real error detached.
- Do not weaken a test, an assertion or a threshold to make a gate pass. Fix the code, or ask the owner.
