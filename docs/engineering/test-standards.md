# Test standards — CineFilm

Adapted from goldenhour's `docs/engineering/test-improvement-standards.md` and `frontend-test-standards.md` and from
CineLight's `test-standards.md` (2026-11-08). Read the section for the side you are working on before you write or
change a test.

- **Backend:** the "Backend" section below (JUnit 5, Mockito, MockMvc, Testcontainers).
- **Frontend:** the "Frontend" section after it.

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

# Backend

JUnit 5, Mockito (strict stubs), AssertJ, Spring's MockMvc and `@WebMvcTest`, Testcontainers for the CI-only
integration package. Tests mirror the main packages (`gregochr.cinefilm.playback.PlaybackService` →
`.../playback/PlaybackServiceTest`); shared builders and the clock live in `gregochr.cinefilm.TestData`. Run the
local gate and read `dev-and-testing.md` for the build ladder.

## Which kind of test

| Kind | Use it for | Name | Runs |
|---|---|---|---|
| **Unit** (`@ExtendWith(MockitoExtension.class)` or plain construction) | every service, rule, mapper and filter: the behaviour, its boundaries and its error messages | `XxxTest` | every build, locally |
| **Controller slice** (`@WebMvcTest(XController.class)` + `@Import(SecurityConfig.class)` + `@MockitoBean` service) | the HTTP contract of one controller: which request values reach the service, the status and body shape, the 401 and missing-header cases | `XxxControllerTest` | every build, locally |
| **API test** (`ApiTestBase`: full `@SpringBootTest`, MockMvc, H2, rolled back per test) | the contract end to end across service, JPA and JSON for a feature | `XxxApiTest` | every build, locally |
| **Whole-app guards** (`RouteSecurityDriftTest`, `ActuatorTest`, `SessionCookieTest`, `DevSeedTest`, `DevSeedSampleVideoTest`) | things only the full context can show: routes, security chain, cookie attributes, the seed | named for the guard | every build, locally |
| **Integration** (`integration/` package, `IntegrationTestBase`, Postgres 17 Testcontainers) | Flyway migrations and queries on real PostgreSQL | `XxxIntegrationTest` | **CI only** (the Mac has no Docker) |

Rule: **every service has a unit test class of its own**, and a behaviour is tested at the lowest level that can
show it. An HTTP test is not a substitute for a service test (the service test is cheap, exact and kills mutants;
the HTTP test proves the wiring). Keep the API tests, they are the contract the frontend and any future native
client depend on. A new `@SpringBootTest` needs a reason a slice or a unit test cannot serve.

## Smells to eliminate

1. **`any()` in `verify()`.** Assert the exact argument, or capture it with `ArgumentCaptor` and assert its fields.
   `verify(preferences).rememberLastEdition(FILM_ID, WITHOUT_MARKER)`, never `verify(preferences).rememberLastEdition(any(), any())`.
2. **`any()` in `when()` where the input is knowable.** Stub with the real argument so a wrong argument fails the
   test. The one exception is a stub whose argument is the thing under construction (see `loaderWrapsFilms` in
   `PersonServiceTest`).
3. **`lenient()` stubs.** If Mockito reports a stub as unnecessary, delete it or fix the test; do not silence it.
   Stub inside the test that needs it, not in `@BeforeEach`.
4. **Missing boundaries.** Every threshold gets a test one below, exactly at and one above: the watched rule
   (`90 * MIN - 1` and `90 * MIN`), the 30-day recently-added window, the five-calendar-year cutoff, `positionMs`
   0 and -1, the five films a theme needs. "Boundary" for time means milliseconds, not days.
5. **`@SpringBootTest` where a unit test will do.**
6. **Time** (below).
7. **Existence instead of value.** `jsonPath("$.error").exists()` proves nothing. **Assert the exact error body**:
   `jsonPath("$.error").value("No such film")`. Every 4xx assertion carries the `$.error` text; the 401 body is
   `Authentication required`, the missing CSRF header body is `Missing X-CineFilm header`, a malformed body or id is
   `Invalid request`. Likewise assert `positionMs`, ids and dates against the value the fixture set, not that a key
   is present. (Server-generated ids are the one fair `exists()`.)
8. **Assertions on implementation.** Do not assert how many times a repository was called, in what order, or that a
   private helper ran. Assert the result and the saved state.

## Time

- Production code reads the time only through the injected `java.time.Clock`. No `Instant.now()`,
  `LocalDate.now()`, `Clock.systemDefaultZone()` or `ZoneId.systemDefault()` in `src/main` (`LocalDate.now(clock)`
  is fine). The application zone is `Europe/London`, set explicitly in `application.properties`
  (`cinefilm.time-zone`), so a container in UTC behaves the same as the dev Mac.
- **Every test uses `TestData.CLOCK`**: 2024-03-31T23:30:00Z in `Europe/London`. It is chosen so it can never be the
  day the suite runs (the mutation these tests exist to catch is "the code ignores the injected clock", and code
  that reads the real date passes a fixture only on the day it was written), and it sits on two edges at once: in
  UTC it is Sunday 31 March (ISO week 2024-W13), but British Summer Time began 23 hours earlier, so in London it is
  already Monday 1 April, 00:30 (week key 202414) and the weekly theme has rolled over. A test that reads the UTC
  date, or the machine's zone, gets the wrong theme.
- Full-context tests get that clock from `TestClockConfig` (profile `test`, `@Primary`); unit tests pass
  `TestData.CLOCK` to the constructor; `TestData.NOW` and `TestData.ZONE` are the instant and zone of it. Express
  fixture times as offsets from `NOW` (`NOW.minus(Duration.ofDays(30))`).
- **No `Instant.now()` and no `Clock.systemDefaultZone()` in a test.** A test that needs a different instant builds
  its own `Clock.fixed(...)` in the zone it is testing, and picks dates that exercise an edge: an ISO-week rollover
  (2024-12-30 is already week 2025-W01), a 53-week year (2020-12-31 and 2021-01-03 are 2020-W53), the BST changeover.
- No `Thread.sleep()`. Inject a clock or a scheduler you control.

## Security: the route-drift test

`RouteSecurityDriftTest` discovers every mapped `/api/**` route from Spring's handler mappings and fails when any
route other than the three session endpoints (`GET`, `POST`, `DELETE /api/session`) answers a signed-out caller with
anything but the `401` JSON `Authentication required`. It needs no maintenance: a new controller route is picked up
automatically, and an unprotected one fails the build. The same file proves the discovery is not vacuous (it lists
the known routes) and that, signed in, the same requests get past security. If you add a route that must be open,
add it to `SESSION_ROUTES` **and** say why in the PR; the owner reviews that line. Controller slices still carry
their own 401 and missing-header cases (AGENTS.md rule 5).

## Tags

| Tag | Meaning | Runs |
|---|---|---|
| *(none)* | Unit, slice, API test | every build |
| `real-api` | calls a live third-party service (TMDB, later) | never by default: `surefire.excludedGroups=real-api`; run with `-Dsurefire.excludedGroups= -Dgroups=real-api` from its own workflow |

The `integration` package is excluded by path (`-Dtest='!**/integration/**'`), not by tag, because the local gate
has to leave out a package, not a label.

## Coverage gates

`./mvnw clean verify` runs JaCoCo's `check` after the tests, goldenhour's numbers:

1. **Per class: 95% lines and 90% branches** for every class under `gregochr.cinefilm`, no exclusions. The old
   blanket exclusions of `CineFilmApplication` and `seed/**` are gone: the application class is covered by
   `CineFilmApplicationTest` (`main` hands its class and arguments to Spring) and `DevSeed` by `DevSeedTest`.
2. **A bundle floor at the measured total**, rounded down to one decimal place: **99.7% lines, 99.1% branches**
   (measured 2026-11-08 on the local gate: 99.723% lines, 99.127% branches). Ratchet: raise it when coverage rises.
3. **One per-class override**, with its reason in `backend/pom.xml`: `auth.PasswordChecker` is held to 85% lines and
   100% branches because the `NoSuchAlgorithmException` catch after a SHA-256 lookup (2 of 14 lines) cannot run on
   any JVM. Measured 12/14 lines.

Lowest classes today (line / branch): `PasswordChecker` 85.7% / 100% (the override), `WeeklyThemeSelector` 100% /
94.4% (17 of 18 branches), `BrowseRules` 100% / 97.6% (41 of 42); every other class is 100% / 100%. `DevSeed` is
100% / 100% since `DevSeedSampleVideoTest` boots it with `cinefilm.sample-video` set.

**Integration tests and the gate.** The per-class rule is computed on the unit and slice suite alone (that is what the
local gate runs), so every class already clears 95/90 without the Testcontainers classes. CI runs everything, so its
figures can only be higher; the floors are set on the local numbers so a PR is never green locally and red in CI. If
a class ever needs an integration test to reach 95/90, do what goldenhour does: write the class its own unit test
anyway, and only if that is impossible give it a per-class rule at the local figure with the reason beside it.

**Ratchet rule: raise the numbers when coverage rises; never lower them without the owner's say-so.** Never add an
exclusion or an override to fit a change; write the test. An override is for a line that truly cannot run, written at
the measured figure with the line and reason in an XML comment.

## SpotBugs and FindSecBugs

Effort Max, threshold High, FindSecBugs 1.14.0, bound to `verify`: any High-confidence finding fails the build. The
exclude file `backend/spotbugs-exclude.xml` holds the false positives, each `<Match>` preceded by a comment saying
why this code is safe. Fix the code first; suppress only a finding that cannot be a bug here, never to get a build
through, and never with `@SuppressFBWarnings` without the same justification.

## Checkstyle

Javadoc on every public type, method and constructor in `src/main` (a one-line summary is enough; `@param` and
`@return` are optional, but a tag you write must be valid); tests are exempt from the Javadoc check and from nothing
else. 4-space indentation (wrapped lines at least 4 further in), 120 columns, no unused or star imports.

## Process for a new class or a change

1. Read the class under test first, and the tests next to it.
2. Write the unit test of the behaviour and its boundaries, with exact values and exact error messages.
3. Add or extend the slice test if the HTTP contract changed, and the API test if the end-to-end shape changed;
   update `foundation-plan.md` when a request or response shape changes (AGENTS.md rule 4).
4. Run the local gate (`dev-and-testing.md`), exit code 0. Look at `target/site/jacoco` for the classes you touched.
5. Prove a new test can fail: mutate the code in a copy and watch it go red (see below).

## Mutation-checking a test without destroying work

Mutating production code to prove a test can fail is encouraged. Restoring it is where work gets lost.

- **Never restore with `git checkout -- <path>`, `git restore` or `git stash`.** They revert to HEAD and silently
  delete any uncommitted change in the file, usually the very change under test.
- Back up with `cp` and restore with `cp`, or commit first, or mutate in a separate worktree.
- Confirm the restore: `git diff` on the file shows exactly the change you started with.
- PIT (weekly mutation testing) arrives in phase 5 of `ci-test-deploy-plan.md`; it is why the services need unit
  tests of their own: full-context tests are too slow to kill mutants.

## What not to do (backend)

- Do not add tests that assert nothing meaningful (a constructor sets a field) to move a coverage number.
- Do not add `@Disabled` tests or `lenient()` stubs: fix them or delete them.
- Do not test private methods directly; test through the public API.
- Do not read the real clock in a test, or depend on the machine's time zone or locale.
- Do not weaken a test, an assertion, an exclusion or a threshold to make a gate pass. Fix the code, or ask the owner.

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
