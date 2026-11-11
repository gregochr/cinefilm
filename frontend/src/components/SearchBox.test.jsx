import { readFileSync } from 'node:fs';
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { buildFixtureSearchIndex } from '../api/fixtureSearchIndex.js';
import { prepareIndex, useSearchIndex } from '../state/searchIndex.js';
import Header from './Header.jsx';
import { panelPlacement } from './SearchBox.jsx';
import SearchResults, { Highlighted } from './SearchResults.jsx';

vi.mock('../state/searchIndex.js', async (importOriginal) => ({
  ...(await importOriginal()),
  useSearchIndex: vi.fn(),
}));

const FIXTURE = prepareIndex(buildFixtureSearchIndex());
const ready = (index = FIXTURE) => useSearchIndex.mockReturnValue({ index, status: 'ready' });

function Where() {
  const { pathname, search } = useLocation();
  return <output aria-label="Location">{`${pathname}${search}`}</output>;
}

function setup(path = '/') {
  const user = userEvent.setup();
  render(
    <MemoryRouter initialEntries={[path]}>
      <Header />
      <Where />
    </MemoryRouter>,
  );
  return { user, box: screen.getByRole('combobox', { name: 'Search the library' }) };
}

// The option id carries a per-instance prefix; tests compare the stable tail (`titles-1`).
const stem = (o) => o.id.replace(/^.*-opt-/, '');
const live = () => screen.getByRole('status', { name: 'Search status' });
const where = () => screen.getByLabelText('Location').textContent;
const options = () => screen.queryAllByRole('option');
const optionNames = () => options().map((o) => within(o).getByTestId('search-name').textContent);
const groups = () => screen.queryAllByRole('group').map((g) => g.getAttribute('aria-label'));
const active = () => options().filter((o) => o.getAttribute('aria-selected') === 'true');

const raw = (over) => ({ films: [], collections: [], people: [], ...over });

beforeEach(() => {
  ready();
});

describe('SearchBox opening', () => {
  it('opens at one character and shows the listbox under the box', async () => {
    const { user, box } = setup();
    expect(box).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    await user.type(box, 'w');
    expect(box).toHaveAttribute('aria-expanded', 'true');
    expect(box).toHaveAttribute('aria-controls', screen.getByRole('listbox').id);
    expect(box).toHaveAttribute('aria-autocomplete', 'list');
    expect(box).toHaveAttribute('placeholder', 'Title, actor or director');
  });

  it('does not open for whitespace', async () => {
    const { user, box } = setup();
    await user.type(box, '   ');
    expect(box).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('swaps the / hint for Esc while open', async () => {
    const { user, box } = setup();
    const hint = () => screen.getByTestId('search-hint').textContent;
    expect(hint()).toBe('/');
    await user.type(box, 'w');
    expect(hint()).toBe('Esc');
  });

  it('is hidden on the Library pages, which have their own', () => {
    render(
      <MemoryRouter initialEntries={['/library']}>
        <Header />
      </MemoryRouter>,
    );
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
  });

  it('reopens on focus when a query is still in the box', async () => {
    const { user, box } = setup();
    await user.type(box, 'war');
    await user.click(screen.getByTestId('search-scrim'));
    expect(box).toHaveAttribute('aria-expanded', 'false');
    expect(box).toHaveValue('war');
    box.blur();
    await user.click(box);
    expect(box).toHaveAttribute('aria-expanded', 'true');
  });
});

describe('groups and rows', () => {
  it('renders Titles, Collections, Actors, Directors in that order, only the ones that match', async () => {
    ready(
      prepareIndex(
        raw({
          films: [{ id: 1, t: 'Zed' }],
          collections: [{ id: 2, n: 'Zed Saga Collection', o: 2, of: 3, p: [] }],
          people: [
            { id: 3, n: 'Zed Actor', a: 2, d: 0 },
            { id: 4, n: 'Zed Director', a: 0, d: 2 },
          ],
        }),
      ),
    );
    const { user, box } = setup();
    await user.type(box, 'zed');
    expect(groups()).toEqual(['Titles', 'Collections', 'Actors', 'Directors']);
    expect(options().map(stem)).toEqual(['titles-1', 'collections-2', 'actors-3', 'directors-4']);
  });

  it('finds the "ha" sample results', async () => {
    const { user, box } = setup();
    await user.type(box, 'ha');
    expect(groups()).toEqual(['Titles', 'Collections', 'Actors']);
    const names = optionNames();
    expect(names).toEqual(
      expect.arrayContaining([
        'Hancock',
        'The Hangover',
        'Harry Potter and the Philosopher’s Stone',
        'Harry Potter and the Chamber of Secrets',
        'Harry Potter',
        'Tom Hanks',
      ]),
    );
    const actors = within(screen.getByRole('group', { name: 'Actors' })).getAllByRole('option');
    expect(actors.map((a) => a.textContent)).toEqual(
      expect.arrayContaining([expect.stringContaining('Tom Hanks')]),
    );
  });

  it('leaves out groups without matches', async () => {
    const { user, box } = setup();
    await user.type(box, 'warrior');
    expect(groups()).toEqual(['Titles']);
  });

  it('shows the count, or "N of M" when a group is capped', async () => {
    ready(
      prepareIndex(
        raw({
          people: Array.from({ length: 7 }, (_, i) => ({
            id: i + 1,
            n: `Sam Person${i}`,
            a: i + 1,
          })),
        }),
      ),
    );
    const { user, box } = setup();
    await user.type(box, 'sam');
    expect(within(screen.getByRole('group', { name: 'Actors' })).getByText('3 of 7')).toBeVisible();
    expect(options()).toHaveLength(3);
    // Person1.. have the most films, so they lead.
    expect(optionNames()[0]).toBe('Sam Person6');
  });

  it('shows a plain count when nothing is capped', async () => {
    const { user, box } = setup();
    await user.type(box, 'warrior');
    expect(within(screen.getByRole('group', { name: 'Titles' })).getByText('1')).toBeVisible();
  });

  it('title rows: poster, year and director, time left only when in progress', async () => {
    const { user, box } = setup();
    await user.type(box, 'war');
    const warrior = screen.getByRole('option', { name: /Warrior/ });
    expect(warrior).toHaveTextContent("2011 · Gavin O'Connor");
    expect(warrior).toHaveTextContent('2h 3m left');
    expect(within(warrior).getByTestId('art')).toBeInTheDocument();
    const horse = screen.getByRole('option', { name: /War Horse/ });
    expect(horse).toHaveTextContent('2011 · Steven Spielberg');
    expect(horse).not.toHaveTextContent('left');
  });

  it('title rows use the small poster size', async () => {
    ready(prepareIndex(raw({ films: [{ id: 202, t: 'War Horse', p: '/horse.jpg' }] })));
    const { user, box } = setup();
    await user.type(box, 'war horse');
    const img = within(screen.getByRole('option', { name: /War Horse/ })).getByRole(
      'presentation',
      {
        hidden: true,
      },
    );
    expect(img.getAttribute('src')).toContain('/w92/');
  });

  it('has no stray separator when the year or director is missing', async () => {
    ready(
      prepareIndex(
        raw({
          films: [
            { id: 1, t: 'Alpha', y: 2001 },
            { id: 2, t: 'Alpine', d: ['Jo Director'] },
            { id: 3, t: 'Alps' },
          ],
        }),
      ),
    );
    const { user, box } = setup();
    await user.type(box, 'alp');
    const meta = (name) => within(screen.getByRole('option', { name })).getByTestId('search-meta');
    expect(meta(/Alpha/).textContent).toBe('2001');
    expect(meta(/Alpine/).textContent).toBe('Jo Director');
    expect(meta(/Alps/).textContent).toBe('');
  });

  it('collection rows: a fan, the display name, and "Collection · n of m films"', async () => {
    const { user, box } = setup();
    await user.type(box, 'star wa');
    const row = within(screen.getByRole('group', { name: 'Collections' })).getByRole('option');
    expect(row).toHaveTextContent('Star Wars');
    expect(row).not.toHaveTextContent('Star Wars Collection');
    expect(row).toHaveTextContent('Collection · 2 of 9 films');
    expect(within(row).getByTestId('fan')).toBeInTheDocument();
    expect(within(row).getAllByTestId('fan-poster')).toHaveLength(2);
  });

  it('people rows: initials, role and film count (1 film singular)', async () => {
    const { user, box } = setup();
    await user.type(box, 'tom');
    const row = screen.getByRole('option', { name: /Tom Hanks/ });
    expect(row).toHaveTextContent('TH');
    expect(row).toHaveTextContent('Actor · 9 films');
    await user.clear(box);
    await user.type(box, 'gavin');
    expect(screen.getByRole('option', { name: /Gavin/ })).toHaveTextContent('Director · 1 film');
  });

  it('a person who is an actor and a director has distinct option ids in both groups', async () => {
    const { user, box } = setup();
    await user.type(box, 'peter b');
    const ids = options().map(stem);
    expect(ids).toContain('actors-305');
    expect(ids).toContain('directors-305');
    expect(new Set(ids).size).toBe(ids.length);
  });
});

describe('highlight', () => {
  const marks = (row) =>
    within(row)
      .queryAllByTestId('search-hit')
      .map((m) => m.textContent);

  it('wraps exactly the matched prefix', async () => {
    const { user, box } = setup();
    await user.type(box, 'wa');
    expect(marks(screen.getByRole('option', { name: /Walk the Line/ }))).toEqual(['Wa']);
    expect(marks(screen.getByRole('option', { name: /Christoph Waltz/ }))).toEqual(['Wa']);
  });

  it('marks a later word and several terms', async () => {
    const { user, box } = setup();
    await user.type(box, 'star wa');
    const row = within(screen.getByRole('group', { name: 'Collections' })).getByRole('option');
    expect(marks(row)).toEqual(['Star', 'Wa']);
  });

  it('maps back through accents and apostrophes', async () => {
    ready(
      prepareIndex(
        raw({
          films: [{ id: 1, t: 'Amélie' }],
          people: [{ id: 2, n: 'Gavin O’Connor', a: 0, d: 1 }],
        }),
      ),
    );
    const { user, box } = setup();
    await user.type(box, 'amel');
    expect(marks(screen.getByRole('option', { name: /Amélie/ }))).toEqual(['Amél']);
    await user.clear(box);
    await user.type(box, 'ocon');
    expect(marks(screen.getByRole('option', { name: /Gavin/ }))).toEqual(['O’Con']);
  });

  it('Highlighted tolerates overlapping, out-of-order and empty ranges', () => {
    const { container } = render(
      <Highlighted
        text="abcdef"
        ranges={[
          { start: 3, end: 5 },
          { start: 0, end: 2 },
          { start: 1, end: 4 },
          { start: 4, end: 4 },
          { start: 5, end: 99 },
        ]}
      />,
    );
    expect(container.textContent).toBe('abcdef');
    expect(screen.queryAllByTestId('search-hit').map((m) => m.textContent)).toEqual([
      'ab',
      'cd',
      'e',
      'f',
    ]);
  });

  it('Highlighted renders plain text without ranges', () => {
    const { container } = render(<Highlighted text="plain" />);
    expect(screen.queryAllByTestId('search-hit')).toHaveLength(0);
    expect(container.textContent).toBe('plain');
  });
});

describe('keyboard', () => {
  it('the first row is active and Enter opens it: a film', async () => {
    const { user, box } = setup();
    await user.type(box, 'warrior');
    expect(active()).toHaveLength(1);
    expect(options()[0]).toHaveAttribute('aria-selected', 'true');
    await user.keyboard('{Enter}');
    expect(where()).toBe('/films/204');
  });

  it('Enter opens a collection', async () => {
    ready(
      prepareIndex(raw({ collections: [{ id: 40, n: 'Saga Collection', o: 2, of: 3, p: [] }] })),
    );
    const { user, box } = setup();
    await user.type(box, 'saga');
    expect(stem(options()[0])).toBe('collections-40');
    await user.keyboard('{Enter}');
    expect(where()).toBe('/collections/40');
  });

  it('Enter opens a person', async () => {
    const { user, box } = setup();
    await user.type(box, 'tom han');
    await user.keyboard('{Enter}');
    expect(where()).toBe('/people/300');
  });

  it('after opening, the box is cleared, closed and blurred', async () => {
    const { user, box } = setup();
    await user.type(box, 'warrior{Enter}');
    expect(box).toHaveValue('');
    expect(box).toHaveAttribute('aria-expanded', 'false');
    expect(box).not.toHaveFocus();
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('the first row is active again after every query change', async () => {
    const { user, box } = setup();
    await user.type(box, 'ha');
    await user.keyboard('{ArrowDown}{ArrowDown}');
    expect(options()[2]).toHaveAttribute('aria-selected', 'true');
    await user.type(box, 'n');
    expect(options()[0]).toHaveAttribute('aria-selected', 'true');
  });

  it('arrows cross from Titles into Collections and clamp at both ends', async () => {
    const { user, box } = setup();
    await user.type(box, 'ha');
    const all = options();
    const lastTitle = all.findLastIndex((o) => stem(o).startsWith('titles-'));
    const firstCollection = all.findIndex((o) => stem(o).startsWith('collections-'));
    expect(firstCollection).toBe(lastTitle + 1);

    await user.keyboard('{ArrowUp}');
    expect(all[0]).toHaveAttribute('aria-selected', 'true');
    for (let i = 0; i < lastTitle; i++) await user.keyboard('{ArrowDown}');
    expect(all[lastTitle]).toHaveAttribute('aria-selected', 'true');
    await user.keyboard('{ArrowDown}');
    expect(all[firstCollection]).toHaveAttribute('aria-selected', 'true');
    expect(box).toHaveAttribute('aria-activedescendant', all[firstCollection].id);

    for (let i = 0; i < all.length + 3; i++) await user.keyboard('{ArrowDown}');
    expect(all.at(-1)).toHaveAttribute('aria-selected', 'true');
    await user.keyboard('{Enter}');
    expect(where()).toMatch(/^\/(people|films|collections)\//);
  });

  it('Enter opens the row the arrows moved to', async () => {
    const { user, box } = setup();
    await user.type(box, 'ha');
    await user.keyboard('{ArrowDown}');
    const second = options()[1];
    const target = stem(second).replace('titles-', '/films/');
    await user.keyboard('{Enter}');
    expect(where()).toBe(target);
  });

  it('arrows do nothing while closed', async () => {
    const { user, box } = setup();
    box.focus();
    await user.keyboard('{ArrowDown}{ArrowUp}');
    expect(box).toHaveAttribute('aria-expanded', 'false');
  });

  it('Esc clears and closes; a second Esc blurs', async () => {
    const { user, box } = setup();
    await user.type(box, 'war');
    await user.keyboard('{Escape}');
    expect(box).toHaveValue('');
    expect(box).toHaveAttribute('aria-expanded', 'false');
    expect(box).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(box).not.toHaveFocus();
  });

  it('Esc after an outside click still clears the leftover query first', async () => {
    const { user, box } = setup();
    await user.type(box, 'war');
    await user.click(screen.getByTestId('search-scrim'));
    box.focus();
    await user.keyboard('{Escape}');
    expect(box).toHaveValue('');
    expect(box).toHaveFocus();
  });

  it('ignores other keys', async () => {
    const { user, box } = setup();
    await user.type(box, 'wa');
    await user.keyboard('{Tab}');
    expect(box).toHaveAttribute('aria-expanded', 'true');
  });

  it('keeps the active row in view', async () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    try {
      const { user, box } = setup();
      await user.type(box, 'ha');
      scrollIntoView.mockClear();
      await user.keyboard('{ArrowDown}');
      expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' });
    } finally {
      delete Element.prototype.scrollIntoView;
    }
  });
});

describe('mouse', () => {
  it('hover sets the active row, and only that row changes', async () => {
    const { user, box } = setup();
    await user.type(box, 'ha');
    const target = options()[3];
    await user.hover(target);
    expect(target).toHaveAttribute('aria-selected', 'true');
    expect(active()).toHaveLength(1);
    expect(box).toHaveAttribute('aria-activedescendant', target.id);
  });

  it('clicking a row opens it', async () => {
    const { user, box } = setup();
    await user.type(box, 'war horse');
    await user.click(screen.getByRole('option', { name: /War Horse/ }));
    expect(where()).toBe('/films/202');
    expect(box).toHaveValue('');
  });

  it('clicking outside (the scrim) closes and keeps the text', async () => {
    const { user, box } = setup();
    await user.type(box, 'war');
    await user.click(screen.getByTestId('search-scrim'));
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    expect(box).toHaveValue('war');
  });

  it('pressing inside the panel does not take the focus from the input', async () => {
    const { user, box } = setup();
    await user.type(box, 'war');
    await user.pointer({ keys: '[MouseLeft>]', target: screen.getByRole('listbox') });
    expect(box).toHaveFocus();
  });

  it('closes when the route changes from elsewhere', async () => {
    const { user, box } = setup('/films/1');
    await user.type(box, 'war');
    await user.click(screen.getByRole('link', { name: 'Home' }));
    expect(box).toHaveAttribute('aria-expanded', 'false');
  });
});

describe('footer', () => {
  it('shows the hints, and no See all when nothing is capped', async () => {
    const { user, box } = setup();
    await user.type(box, 'warrior');
    expect(screen.getByText('↑↓ move · Enter open · Esc close')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /See all/ })).not.toBeInTheDocument();
  });

  it('shows See all N results to the Library when a group is capped', async () => {
    const { user, box } = setup();
    await user.type(box, 'wa');
    const link = screen.getByRole('link', { name: /See all \d+ results/ });
    const total = /(\d+)/.exec(link.textContent)[1];
    expect(Number(total)).toBeGreaterThan(options().length);
    expect(link).toHaveAttribute('href', '/library?q=wa');
    await user.click(link);
    expect(where()).toBe('/library?q=wa');
    expect(box.isConnected).toBe(false);
  });

  it('encodes the query in the See all link', async () => {
    ready(
      prepareIndex(
        raw({ films: Array.from({ length: 6 }, (_, i) => ({ id: i + 1, t: `Star Wars ${i}` })) }),
      ),
    );
    const { user, box } = setup();
    await user.type(box, ' star wa ');
    const link = screen.getByRole('link', { name: 'See all 6 results' });
    expect(link).toHaveAttribute('href', '/library?q=star%20wa');
  });
});

describe('states', () => {
  it('shows the fuzzy notice above the groups', async () => {
    const { user, box } = setup();
    await user.type(box, 'warripr');
    expect(
      screen.getByText('No exact match for “warripr” · showing close matches'),
    ).toBeInTheDocument();
    expect(optionNames()).toEqual(['Warrior']);
    await user.keyboard('{Enter}');
    expect(where()).toBe('/films/204');
  });

  it('has no fuzzy notice for an exact match', async () => {
    const { user, box } = setup();
    await user.type(box, 'warrior');
    expect(screen.queryByText(/No exact match/)).not.toBeInTheDocument();
  });

  it('says so when nothing matches, and Enter falls back to the Library', async () => {
    const { user, box } = setup();
    await user.type(box, 'qzxqzx');
    expect(screen.getByTestId('search-empty-title')).toHaveTextContent('No matches for “qzxqzx”');
    expect(
      screen.getByText('Search looks at titles, collections, actors and directors.'),
    ).toBeInTheDocument();
    expect(options()).toHaveLength(0);
    expect(box).not.toHaveAttribute('aria-activedescendant');
    expect(screen.queryByText('↑↓ move · Enter open · Esc close')).not.toBeInTheDocument();
    await user.keyboard('{Enter}');
    expect(where()).toBe('/library?q=qzxqzx');
  });

  it('shows a quiet Loading… row while the index loads; Enter searches the Library', async () => {
    useSearchIndex.mockReturnValue({ index: null, status: 'loading' });
    const { user, box } = setup();
    await user.type(box, 'blade runner');
    // Announced once, by the one polite live region; the visible note is plain text.
    expect(live()).toHaveTextContent('Loading…');
    expect(screen.getAllByRole('status')).toContain(live());
    expect(live()).toHaveAttribute('aria-live', 'polite');
    expect(live()).toHaveAttribute('aria-atomic', 'true');
    expect(screen.getAllByText('Loading…')).toHaveLength(2);
    // The live region is the only status that says it; the visible note is plain text.
    const saying = screen.getAllByRole('status').filter((n) => n.textContent === 'Loading…');
    expect(saying).toEqual([live()]);
    expect(screen.queryAllByRole('alert')).toHaveLength(0);
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
    expect(options()).toHaveLength(0);
    await user.keyboard('{Enter}');
    expect(where()).toBe('/library?q=blade%20runner');
    expect(box).toHaveValue('');
  });

  it('shows a quiet line when the index failed; Enter searches the Library', async () => {
    useSearchIndex.mockReturnValue({ index: null, status: 'error' });
    const { user, box } = setup();
    await user.type(box, 'heat');
    expect(live()).toHaveTextContent(
      'Search isn’t available right now. Enter searches the Library.',
    );
    expect(screen.getAllByText(/Search isn’t available right now/)).toHaveLength(2);
    expect(screen.queryAllByRole('alert')).toHaveLength(0);
    await user.keyboard('{Enter}');
    expect(where()).toBe('/library?q=heat');
  });

  it('Enter on an empty box opens the Library', async () => {
    const { user, box } = setup();
    box.focus();
    await user.keyboard('{Enter}');
    expect(where()).toBe('/library');
    await user.type(box, '   {Enter}');
    expect(where()).toBe('/library');
  });
});

describe('aria', () => {
  it('follows the active row with aria-activedescendant, only while open', async () => {
    const { user, box } = setup();
    expect(box).toHaveAttribute('role', 'combobox');
    expect(box).not.toHaveAttribute('aria-activedescendant');
    await user.type(box, 'ha');
    expect(box).toHaveAttribute('aria-activedescendant', options()[0].id);
    await user.keyboard('{ArrowDown}');
    expect(box).toHaveAttribute('aria-activedescendant', options()[1].id);
    await user.keyboard('{Escape}');
    expect(box).not.toHaveAttribute('aria-activedescendant');
  });

  it('labels each section and every option has an id', async () => {
    const { user, box } = setup();
    await user.type(box, 'ha');
    for (const group of screen.getAllByRole('group')) {
      expect(group).toHaveAccessibleName();
    }
    for (const option of options()) expect(option.id).toMatch(/-opt-/);
  });

  it('announces the count, and only when it changes', async () => {
    const { user, box } = setup();
    const region = live();
    expect(region).toHaveTextContent('');
    await user.type(box, 'w');
    const first = region.textContent;
    expect(first).toMatch(/^\d+ results$/);
    await user.type(box, 'ar');
    const second = region.textContent;
    expect(second).toMatch(/^\d+ results?$/);
    expect(second).not.toBe(first);
    await user.type(box, 'rior');
    expect(region).toHaveTextContent('1 result');
    await user.type(box, 'zzzz');
    expect(region).toHaveTextContent('No matches for “warriorzzzz”');
    await user.keyboard('{Escape}');
    expect(region).toHaveTextContent('');
  });

  it('does not rewrite the live text when a hover changes nothing about the count', async () => {
    const { user, box } = setup();
    await user.type(box, 'ha');
    const region = live();
    const watch = new MutationObserver(() => {});
    watch.observe(region, { childList: true, characterData: true, subtree: true });
    await user.hover(options()[2]);
    await user.keyboard('{ArrowDown}');
    expect(watch.takeRecords()).toHaveLength(0);
    watch.disconnect();
  });
});

describe('narrowing', () => {
  it('W, Wa, War, Warr narrows to Warrior alone and Enter opens it', async () => {
    const { user, box } = setup();
    await user.type(box, 'w');
    const w = options().length;
    await user.type(box, 'a');
    const wa = optionNames();
    await user.type(box, 'r');
    const war = optionNames();
    await user.type(box, 'r');
    expect(w).toBeGreaterThanOrEqual(war.length);
    expect(wa).toEqual(expect.arrayContaining(['Walk the Line', 'WALL·E']));
    expect(war).not.toContain('Walk the Line');
    expect(war).toEqual(expect.arrayContaining(['War Horse', 'Warrior']));
    expect(optionNames()).toEqual(['Warrior']);
    expect(groups()).toEqual(['Titles']);
    await user.keyboard('{Enter}');
    expect(where()).toBe('/films/204');
  });
});

describe('SearchResults size', () => {
  it('lg rows drop the keyboard hints but keep See all', () => {
    const result = {
      query: 'a',
      total: 9,
      fuzzy: false,
      groups: [
        {
          key: 'titles',
          label: 'Titles',
          total: 9,
          items: [{ id: 1, title: 'Alpha', subtitle: '', ranges: [], leftMs: null }],
        },
      ],
    };
    const rows = [
      {
        index: 0,
        groupKey: 'titles',
        id: 'search-opt-titles-1',
        path: '/films/1',
        item: result.groups[0].items[0],
      },
    ];
    render(
      <MemoryRouter>
        <SearchResults
          status="ready"
          result={result}
          rows={rows}
          activeIndex={0}
          listboxId="lb"
          size="lg"
          onHover={() => {}}
          onOpen={() => {}}
        />
      </MemoryRouter>,
    );
    expect(screen.getByTestId('search-results')).toHaveClass('search-results--lg');
    expect(screen.queryByText(/Enter open/)).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'See all 9 results' })).toBeInTheDocument();
  });
});

describe('search.css', () => {
  it('makes the See all link a 44px touch target', () => {
    const css = readFileSync(`${process.cwd()}/src/styles/search.css`, 'utf8');
    expect(css).toMatch(/\.search-footer__all \{[^}]*display: inline-flex;[^}]*min-height: 44px;/);
  });
});

describe('panel placement', () => {
  const rect = (left, right) => ({
    left,
    right,
    top: 0,
    bottom: 40,
    width: right - left,
    height: 40,
  });
  let boxRect;
  let spy;

  beforeEach(() => {
    spy = vi
      .spyOn(HTMLElement.prototype, 'getBoundingClientRect')
      .mockImplementation(() => boxRect);
  });
  afterEach(() => {
    spy.mockRestore();
    window.innerWidth = 1024;
  });

  const panel = () => screen.getByRole('listbox').closest('.search-panel');

  it('pure: right-aligned when it fits, else left-aligned with the width clamped to the viewport', () => {
    expect(panelPlacement(rect(640, 940), 980)).toEqual({ align: 'right', width: null });
    expect(panelPlacement(rect(556, 856), 1000)).toEqual({ align: 'right', width: null });
    expect(panelPlacement(rect(40, 340), 768)).toEqual({ align: 'left', width: 540 });
    expect(panelPlacement(rect(40, 340), 400)).toEqual({ align: 'left', width: 344 });
    expect(panelPlacement(rect(500, 540), 510)).toEqual({ align: 'left', width: 0 });
  });

  it('is right-aligned to a box with room on its left', async () => {
    boxRect = rect(640, 940);
    window.innerWidth = 980;
    const { user, box } = setup();
    await user.type(box, 'w');
    expect(panel()).not.toHaveClass('search-panel--left');
    expect(panel().style.width).toBe('');
  });

  it('is left-aligned to a box at the left, with the width clamped to the viewport', async () => {
    boxRect = rect(40, 340);
    window.innerWidth = 400;
    const { user, box } = setup();
    await user.type(box, 'w');
    expect(panel()).toHaveClass('search-panel--left');
    expect(panel().style.width).toBe('344px');
  });

  it('switches when the window is resized while open', async () => {
    boxRect = rect(640, 940);
    window.innerWidth = 980;
    const { user, box } = setup();
    await user.type(box, 'w');
    expect(panel()).not.toHaveClass('search-panel--left');
    boxRect = rect(40, 340);
    window.innerWidth = 768;
    act(() => {
      window.dispatchEvent(new Event('resize'));
    });
    expect(panel()).toHaveClass('search-panel--left');
    expect(panel().style.width).toBe('540px');
    boxRect = rect(640, 940);
    window.innerWidth = 980;
    act(() => {
      window.dispatchEvent(new Event('resize'));
    });
    expect(panel()).not.toHaveClass('search-panel--left');
    expect(panel().style.width).toBe('');
  });
});
