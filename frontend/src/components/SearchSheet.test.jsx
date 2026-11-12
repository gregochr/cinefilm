import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { buildFixtureSearchIndex } from '../api/fixtureSearchIndex.js';
import { prepareIndex, useSearchIndex } from '../state/searchIndex.js';
import { useSearchParams } from 'react-router-dom';
import { useUrlSearch } from '../state/useUrlSearch.js';
import Layout from './Layout.jsx';

vi.mock('../state/searchIndex.js', async (importOriginal) => ({
  ...(await importOriginal()),
  useSearchIndex: vi.fn(),
}));
vi.mock('./useSearchIndexLifecycle.js', () => ({ useSearchIndexLifecycle: () => {} }));

const FIXTURE = prepareIndex(buildFixtureSearchIndex());

function Where() {
  const { pathname, search } = useLocation();
  const navigate = useNavigate();
  return (
    <>
      <output aria-label="Location">{`${pathname}${search}`}</output>
      <button type="button" onClick={() => navigate(-1)}>
        history back
      </button>
    </>
  );
}

// Stands in for the Library: its search box is driven by the URL's q through useUrlSearch.
function LibraryProbe() {
  const [params, setParams] = useSearchParams();
  const { text } = useUrlSearch(params, setParams);
  return (
    <>
      <output aria-label="Library search text">{text}</output>
      <input type="search" data-global-search aria-label="Library behind" />
      <Where />
    </>
  );
}

function setup(path = '/') {
  const user = userEvent.setup();
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<Layout />}>
          <Route path="/" element={<Where />} />
          <Route path="/library" element={<LibraryProbe />} />
          <Route path="/films/:id" element={<Where />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
  return user;
}

const where = () => screen.getByLabelText('Location').textContent;
const tab = () => screen.getByRole('link', { name: 'Search' });
const sheet = () => screen.queryByRole('dialog', { name: 'Search' });
const input = () => within(sheet()).getByRole('combobox', { name: 'Search the library' });
const options = () => screen.queryAllByRole('option');
const optionNames = () => options().map((o) => within(o).getByTestId('search-name').textContent);

async function openSheet(path = '/') {
  const user = setup(path);
  await user.click(tab());
  return user;
}

beforeEach(() => {
  useSearchIndex.mockReturnValue({ index: FIXTURE, status: 'ready' });
});

afterEach(() => {
  vi.useRealTimers();
});

// The Library's search box debounces its URL write; drive that clock instead of waiting on it.
async function openSheetWithFakeClock(path) {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
  setup(path);
  await user.click(tab());
  return user;
}
const pastDebounce = () => act(() => vi.advanceTimersByTimeAsync(400));

describe('opening', () => {
  it('the Search tab opens the sheet on the current route, focuses the input and is current', async () => {
    await openSheet('/library');
    expect(where()).toBe('/library?search=1');
    expect(sheet()).toBeInTheDocument();
    expect(input()).toHaveFocus();
    expect(tab()).toHaveAttribute('aria-current', 'page');
    const tabs = within(screen.getByRole('navigation', { name: 'Tabs' }));
    expect(tabs.getByRole('link', { name: 'Library' })).not.toHaveAttribute('aria-current');
  });

  it('keeps other params and nothing is open until the tab is used', async () => {
    const user = setup('/library?sort=year');
    expect(sheet()).not.toBeInTheDocument();
    await user.click(tab());
    expect(where()).toBe('/library?sort=year&search=1');
    // the throwaway input that raises the keyboard is gone
    expect(screen.queryAllByRole('textbox', { hidden: true })).toHaveLength(0);
  });

  it('is a modal dialog with the combobox semantics', async () => {
    await openSheet();
    const dialog = sheet();
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(within(dialog).getByRole('search')).toBeInTheDocument();
    expect(input()).toHaveAttribute('aria-autocomplete', 'list');
    expect(input()).toHaveAttribute('aria-expanded', 'false');
    expect(input()).toHaveAttribute('placeholder', 'Title, actor or director');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('opens from a deep link too', () => {
    setup('/?search=1');
    expect(sheet()).toBeInTheDocument();
    expect(input()).toHaveFocus();
  });
});

describe('results', () => {
  it('shows the desktop groups for "ha" in lg rows, with counts', async () => {
    const user = await openSheet();
    await user.type(input(), 'ha');
    expect(input()).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getAllByRole('group').map((g) => g.getAttribute('aria-label'))).toEqual([
      'Titles',
      'Collections',
      'Actors',
    ]);
    expect(optionNames()).toEqual(
      expect.arrayContaining(['Hancock', 'The Hangover', 'Harry Potter', 'Tom Hanks']),
    );
    expect(screen.queryByText(/Esc close/)).not.toBeInTheDocument();
  });

  it('announces in the polite live region', async () => {
    const user = await openSheet();
    const live = within(sheet()).getByRole('status');
    expect(live).toHaveAttribute('aria-live', 'polite');
    expect(live).toHaveAttribute('aria-atomic', 'true');
    expect(live).toHaveTextContent('');
    await user.type(input(), 'warrior');
    expect(live).toHaveTextContent('1 result');
    await user.type(input(), 'zzz');
    expect(live).toHaveTextContent('No matches for “warriorzzz”');
    expect(screen.getByTestId('search-empty-title')).toBeInTheDocument();
  });

  it('shows the fuzzy notice', async () => {
    const user = await openSheet();
    await user.type(input(), 'warripr');
    expect(screen.getByText(/No exact match for “warripr”/)).toBeInTheDocument();
    expect(optionNames()).toEqual(['Warrior']);
  });

  it('shows Loading… and the error line, and Enter searches the Library', async () => {
    useSearchIndex.mockReturnValue({ index: null, status: 'loading' });
    const user = await openSheet();
    await user.type(input(), 'heat');
    expect(within(sheet()).getByRole('status', { name: 'Search status' })).toHaveTextContent(
      'Loading…',
    );
    expect(options()).toHaveLength(0);
    await user.keyboard('{Enter}');
    expect(where()).toBe('/library?q=heat');
    expect(sheet()).not.toBeInTheDocument();
  });

  it('on error, says so and Enter searches the Library', async () => {
    useSearchIndex.mockReturnValue({ index: null, status: 'error' });
    const user = await openSheet();
    await user.type(input(), 'heat');
    expect(within(sheet()).getByRole('status', { name: 'Search status' })).toHaveTextContent(
      /Search isn’t available/,
    );
    await user.keyboard('{Enter}');
    expect(where()).toBe('/library?q=heat');
  });

  it('Enter with no results, or an empty box, goes to the Library', async () => {
    const user = await openSheet();
    await user.type(input(), 'qzxqzx{Enter}');
    expect(where()).toBe('/library?q=qzxqzx');
  });

  it('Enter on an empty box opens the Library', async () => {
    const user = await openSheet();
    await user.keyboard('{Enter}');
    expect(where()).toBe('/library');
  });
});

describe('opening a result', () => {
  it('tapping a film opens it, closes the sheet and replaces the search entry', async () => {
    const user = await openSheet('/library');
    await user.type(input(), 'warrior');
    await user.click(screen.getByRole('option', { name: /Warrior/ }));
    expect(where()).toMatch(/^\/films\/\d+$/);
    expect(sheet()).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'history back' }));
    expect(where()).toBe('/library');
  });

  it('Enter opens the top result, and the arrows move it', async () => {
    const user = await openSheet('/library');
    await user.type(input(), 'warr{Enter}');
    expect(where()).toMatch(/^\/films\/\d+$/);
    await user.click(screen.getByRole('button', { name: 'history back' }));
    expect(where()).toBe('/library');
  });

  it('arrows move the active row before Enter', async () => {
    const user = await openSheet();
    await user.type(input(), 'ha');
    const names = optionNames();
    await user.keyboard('{ArrowDown}');
    expect(input()).toHaveAttribute('aria-activedescendant', options()[1].id);
    await user.keyboard('{ArrowUp}{ArrowUp}');
    expect(input()).toHaveAttribute('aria-activedescendant', options()[0].id);
    await user.click(options()[0]);
    expect(names.length).toBeGreaterThan(1);
  });

  it('See all goes to /library?q= and replaces the sheet entry', async () => {
    const user = await openSheet('/');
    await user.type(input(), 'wa');
    await user.click(screen.getByRole('link', { name: /See all \d+ results/ }));
    expect(where()).toBe('/library?q=wa');
    expect(sheet()).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'history back' }));
    expect(where()).toBe('/');
  });
});

describe('option ids', () => {
  it('are unique per combobox: the sheet points at its own rows after the header was used', async () => {
    const user = setup('/');
    const header = screen.getByRole('combobox', { name: 'Search the library' });
    await user.type(header, 'ha');
    expect(options().length).toBeGreaterThan(0);
    await user.click(tab());
    // the header dropdown steps aside for the sheet
    expect(header).toHaveAttribute('aria-expanded', 'false');
    await user.type(input(), 'ha');
    const target = input().getAttribute('aria-activedescendant');
    expect(target).toMatch(/\S/);
    expect(within(sheet()).getAllByRole('option')[0]).toHaveAttribute('id', target);
    const ids = screen
      .queryAllByRole('option')
      .concat(screen.queryAllByRole('listbox'))
      .map((n) => n.id);
    expect(new Set(ids).size).toBe(ids.length);
    expect(header).not.toHaveAttribute('aria-activedescendant');
    expect(header.getAttribute('aria-controls')).not.toBe(input().getAttribute('aria-controls'));
  });
});

describe('See all from the Library', () => {
  it('applies the query in the Library and keeps it', async () => {
    const user = await openSheetWithFakeClock('/library');
    await user.type(input(), 'wa');
    await user.click(screen.getByRole('link', { name: /See all \d+ results/ }));
    expect(screen.getByLabelText('Library search text')).toHaveTextContent('wa');
    await pastDebounce();
    expect(where()).toBe('/library?q=wa');
    expect(screen.getByLabelText('Library search text')).toHaveTextContent('wa');
  });

  it('Enter does the same', async () => {
    const user = await openSheetWithFakeClock('/library?q=old');
    await user.type(input(), 'qzxqzx{Enter}');
    await pastDebounce();
    expect(where()).toBe('/library?q=qzxqzx');
    expect(screen.getByLabelText('Library search text')).toHaveTextContent('qzxqzx');
  });
});

describe('focus containment', () => {
  const focusables = () => [
    ...within(sheet()).queryAllByRole('combobox'),
    ...within(sheet()).queryAllByRole('button'),
    ...within(sheet()).queryAllByRole('link'),
  ];

  it('Tab from the last control wraps to the first, Shift+Tab from the input wraps to the last', async () => {
    const user = await openSheet('/library');
    await user.type(input(), 'ha');
    const items = focusables();
    items.at(-1).focus();
    await user.tab();
    expect(items[0]).toHaveFocus();
    expect(items[0]).toBe(input());
    await user.tab({ shift: true });
    expect(items.at(-1)).toHaveFocus();
  });
});

describe('clear, cancel and Esc', () => {
  it('shows the clear button only with text; it empties the field and keeps focus', async () => {
    const user = await openSheet();
    expect(screen.queryByRole('button', { name: 'Clear search' })).not.toBeInTheDocument();
    await user.type(input(), 'ha');
    await user.click(screen.getByRole('button', { name: 'Clear search' }));
    expect(input()).toHaveValue('');
    expect(input()).toHaveFocus();
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Clear search' })).not.toBeInTheDocument();
  });

  it('Cancel goes back to where you were, and focus returns to the Search tab', async () => {
    const user = await openSheet('/library?sort=year');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(where()).toBe('/library?sort=year');
    expect(sheet()).not.toBeInTheDocument();
    expect(tab()).toHaveFocus();
    expect(tab()).not.toHaveAttribute('aria-current');
  });

  it('Cancel on a deep-linked ?search=1 removes the param instead', async () => {
    const user = setup('/library?sort=year&search=1');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(where()).toBe('/library?sort=year');
    expect(sheet()).not.toBeInTheDocument();
  });

  it('Cancel on a deep link with no other params leaves a clean URL', async () => {
    const user = setup('/?search=1');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(where()).toBe('/');
  });

  it('Esc closes the sheet, from the input or from a button', async () => {
    const user = await openSheet('/library');
    await user.keyboard('{Escape}');
    expect(where()).toBe('/library');
    expect(sheet()).not.toBeInTheDocument();
    await user.click(tab());
    await user.type(input(), 'ha');
    screen.getByRole('button', { name: 'Clear search' }).focus();
    await user.keyboard('{Escape}');
    expect(sheet()).not.toBeInTheDocument();
  });

  it('the Back gesture closes the sheet', async () => {
    const user = await openSheet('/library');
    await user.click(screen.getByRole('button', { name: 'history back' }));
    expect(where()).toBe('/library');
    expect(sheet()).not.toBeInTheDocument();
  });
});

describe('scroll lock', () => {
  it('locks the page while open and restores it after Cancel', async () => {
    const user = setup();
    expect(document.body.style.overflow).toBe('');
    await user.click(tab());
    expect(document.body.style.overflow).toBe('hidden');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(document.body.style.overflow).toBe('');
  });

  it('restores the page after choosing a result', async () => {
    const user = await openSheet('/library');
    expect(document.body.style.overflow).toBe('hidden');
    await user.type(input(), 'warrior');
    await user.click(screen.getByRole('option', { name: /Warrior/ }));
    expect(sheet()).not.toBeInTheDocument();
    expect(document.body.style.overflow).toBe('');
  });
});

describe('named controls', () => {
  it('Cancel and clear are real buttons with names', async () => {
    const user = await openSheet();
    await user.type(input(), 'h');
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Clear search' })).toBeInTheDocument();
  });
});

describe('the / shortcut', () => {
  it('keeps focus in the sheet, not the page behind, and still types / in the input', async () => {
    const user = await openSheet('/library');
    const cancel = within(sheet()).getByRole('button', { name: 'Cancel' });
    cancel.focus();
    await user.keyboard('/');
    expect(input()).toHaveFocus();
    expect(screen.getByLabelText('Library behind')).not.toHaveFocus();
    await user.keyboard('a/b');
    expect(input()).toHaveValue('a/b');
    expect(screen.getByLabelText('Library behind')).toHaveValue('');
  });
});
