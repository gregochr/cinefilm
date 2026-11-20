import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { announceSignedIn } from '../../api/client.js';
import { prepareIndex, useSearchIndex } from '../../state/searchIndex.js';
import ChristmasAdd from './ChristmasAdd.jsx';
import { STATES_FAILED_TEXT } from './ChristmasAddResults.jsx';

vi.mock('../../state/searchIndex.js', async (importOriginal) => ({
  ...(await importOriginal()),
  useSearchIndex: vi.fn(),
}));

const INDEX = prepareIndex({
  films: [
    { id: 1, t: 'Batman', y: 1989 },
    { id: 2, t: 'Batman Returns', y: 1992 },
    { id: 3, t: 'Gremlins', y: 1984 },
  ],
  collections: [],
  people: [],
});
const ANSWER = [
  { filmId: 1, tmdbId: 101, state: 'NOT_IN' },
  { filmId: 2, tmdbId: 102, state: 'KEYWORDS_PENDING' },
  { filmId: 3, tmdbId: null, state: 'NO_TMDB_ID' },
];
const emptyData = () => ({ inWeek: [], added: [], leftOut: [] });
const issued = () => 1;

beforeEach(() => {
  useSearchIndex.mockReturnValue({ index: INDEX, status: 'ready' });
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url) => {
      const ids = new URL(url, 'http://x').searchParams.get('filmIds').split(',').map(Number);
      return new Response(JSON.stringify(ANSWER.filter((a) => ids.includes(a.filmId))), {
        status: 200,
      });
    }),
  );
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.resetAllMocks();
});

function Harness({ after = null, ...props }) {
  return (
    <>
      <p>outside</p>
      <ChristmasAdd
        data={emptyData()}
        version={1}
        issued={issued}
        busy={new Set()}
        registerButton={() => () => {}}
        refreshKey={0}
        {...props}
      />
      {after}
    </>
  );
}

function setup(props = {}) {
  const onAct = vi.fn();
  const onFix = vi.fn();
  const view = render(<Harness onAct={onAct} onFix={onFix} {...props} />);
  return { onAct, onFix, ...view };
}

const field = () => screen.getByRole('textbox', { name: 'Add a film' });
const status = () => screen.getByRole('status');
const panelList = () => screen.getByRole('list', { name: 'Films in your library' });

async function search(user, text) {
  await user.click(field());
  await user.keyboard(text);
  return screen.findByRole('button', { name: 'Add Batman Returns' });
}

describe('ChristmasAdd on a desktop', () => {
  it('shows no results until something is typed', async () => {
    const user = userEvent.setup();
    setup();
    await user.click(field());
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('opens a panel of results with their states as you type, and says how many in a live region', async () => {
    const user = userEvent.setup();
    setup();
    await search(user, 'batman');
    expect(status()).toHaveTextContent('2 films in your library');
    expect(screen.getByText('Not a Christmas film')).toBeInTheDocument();
    expect(screen.getByText('Keywords not fetched yet')).toBeInTheDocument();
  });

  it('moves real focus with the arrows: field, first result, next, back, and the field above the first', async () => {
    const user = userEvent.setup();
    setup();
    await search(user, 'batman');
    await user.keyboard('{ArrowDown}');
    // The first press lands on the first result, not the second.
    expect(screen.getByRole('button', { name: 'Add Batman' })).toHaveFocus();
    await user.keyboard('{ArrowDown}');
    expect(screen.getByRole('button', { name: 'Add Batman Returns' })).toHaveFocus();
    await user.keyboard('{ArrowDown}');
    expect(screen.getByRole('button', { name: 'Add Batman Returns' })).toHaveFocus();
    await user.keyboard('{ArrowUp}');
    expect(screen.getByRole('button', { name: 'Add Batman' })).toHaveFocus();
    await user.keyboard('{ArrowUp}');
    expect(field()).toHaveFocus();
    await user.keyboard('{ArrowUp}');
    expect(field()).toHaveFocus();
  });

  it('acts on a focused result with Enter or Space, keeping the panel open and focus on it', async () => {
    const user = userEvent.setup();
    const { onAct } = setup();
    await search(user, 'batman');
    await user.keyboard('{ArrowDown}{Enter}');
    expect(onAct).toHaveBeenCalledWith(
      expect.objectContaining({ filmId: 1, tmdbId: 101, title: 'Batman', state: 'NOT_IN' }),
    );
    await user.keyboard(' ');
    expect(onAct).toHaveBeenCalledTimes(2);
    expect(panelList()).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add Batman' })).toHaveFocus();
  });

  it('does nothing on ArrowDown with no results to go to', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise(() => {})),
    );
    const user = userEvent.setup();
    setup();
    await user.click(field());
    await user.keyboard('batman{ArrowDown}');
    expect(within(panelList()).getAllByRole('listitem')).toHaveLength(2);
    expect(field()).toHaveFocus();
  });

  it('closes on Esc from the field and from a result button, and focus returns to the field', async () => {
    const user = userEvent.setup();
    setup();
    await search(user, 'batman');
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
    expect(field()).toHaveFocus();
    expect(field()).toHaveValue('batman');

    await user.keyboard('{ArrowDown}');
    await screen.findByRole('button', { name: 'Add Batman Returns' });
    await user.keyboard('{ArrowDown}{Escape}');
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
    expect(field()).toHaveFocus();
  });

  it('opens again after Esc on a click on the field, or on ArrowDown', async () => {
    const user = userEvent.setup();
    setup();
    await search(user, 'batman');
    await user.keyboard('{Escape}');
    await user.click(field());
    expect(panelList()).toBeInTheDocument();
    await user.keyboard('{Escape}{ArrowDown}');
    expect(panelList()).toBeInTheDocument();
  });

  it('closes on a press outside, and opens again when the field is focused', async () => {
    const user = userEvent.setup();
    setup();
    await search(user, 'batman');
    await user.click(screen.getByText('outside'));
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
    await user.click(field());
    expect(panelList()).toBeInTheDocument();
  });

  it('closes when Tab takes focus past the last result, and not while it stays inside', async () => {
    const user = userEvent.setup();
    setup({ after: <button type="button">after</button> });
    await search(user, 'batman');
    await user.tab();
    expect(screen.getByRole('button', { name: 'Add Batman' })).toHaveFocus();
    expect(panelList()).toBeInTheDocument();
    await user.tab();
    await user.tab();
    expect(screen.getByRole('button', { name: 'after' })).toHaveFocus();
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
  });

  it('opens Fix match for a film with no TMDB id, with the button as the opener', async () => {
    const user = userEvent.setup();
    const { onFix } = setup();
    await user.click(field());
    await user.keyboard('gremlins');
    const button = await screen.findByRole('button', { name: 'Find the film… Gremlins' });
    await user.click(button);
    expect(onFix).toHaveBeenCalledWith({ filmId: 3, title: 'Gremlins' }, button);
    await user.keyboard('{Enter}');
    expect(onFix).toHaveBeenCalledTimes(2);
  });

  it('says when the index is not available', async () => {
    useSearchIndex.mockReturnValue({ index: null, status: 'error' });
    const user = userEvent.setup();
    setup();
    await user.click(field());
    await user.keyboard('batman');
    expect(status()).toHaveTextContent('Search isn’t available right now.');
    expect(screen.queryByRole('listitem')).not.toBeInTheDocument();
  });

  it('announces when the states could not be read, and the rows then have no button', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(null, { status: 500 })),
    );
    const user = userEvent.setup();
    setup();
    await user.click(field());
    await user.keyboard('batman');
    await vi.waitFor(() => expect(status()).toHaveTextContent(STATES_FAILED_TEXT));
    expect(screen.queryByRole('button', { name: /^Add Batman/ })).not.toBeInTheDocument();
  });

  it('gives the results their buttons once a sign-in follows a 401 on /states', async () => {
    const answer = vi.fn(async () => new Response(null, { status: 401 }));
    vi.stubGlobal('fetch', answer);
    const user = userEvent.setup();
    setup();
    await user.click(field());
    await user.keyboard('batman');
    await vi.waitFor(() => expect(status()).toHaveTextContent(STATES_FAILED_TEXT));
    expect(screen.queryByRole('button', { name: /^Add Batman/ })).not.toBeInTheDocument();

    answer.mockImplementation(
      async () => new Response(JSON.stringify(ANSWER.slice(0, 2)), { status: 200 }),
    );
    act(() => announceSignedIn());
    expect(await screen.findByRole('button', { name: 'Add Batman' })).toBeInTheDocument();
  });
});
