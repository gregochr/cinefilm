import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import ChristmasAddResults, {
  SEARCH_ERROR_TEXT,
  SEARCH_LOADING_TEXT,
  headText,
} from './ChristmasAddResults.jsx';

const row = (filmId, title, state, extra = {}) => ({
  id: `opt-${filmId}`,
  filmId,
  tmdbId: 100 + filmId,
  title,
  year: 1989,
  posterPath: null,
  state,
  ...extra,
});

function setup(props = {}) {
  const onAct = vi.fn();
  const registerButton = vi.fn(() => () => {});
  render(
    <ChristmasAddResults
      head="3 films in your library"
      rows={[
        row(1, 'Batman', 'NOT_IN'),
        row(2, 'Batman Returns', 'IN_ADDED'),
        row(3, 'Batman Begins', 'KEYWORDS_PENDING'),
        row(4, 'Gremlins', 'NO_TMDB_ID', { tmdbId: null }),
        row(5, 'Die Hard', null),
      ]}
      busy={new Set()}
      statesFailed={false}
      onAct={onAct}
      registerButton={registerButton}
      {...props}
    />,
  );
  return { onAct, registerButton };
}

const option = (title) =>
  screen
    .getAllByRole('listitem')
    .find((item) => within(item).queryByText(new RegExp(`^${title} · `)));

describe('ChristmasAddResults', () => {
  it('shows each state as a chip or muted line and its one button', () => {
    setup();
    expect(screen.getByRole('status')).toHaveTextContent('3 films in your library');
    expect(screen.getByRole('list', { name: 'Films in your library' })).toBeInTheDocument();
    const notIn = within(option('Batman'));
    expect(notIn.getByText('Not a Christmas film')).toBeInTheDocument();
    expect(notIn.getByRole('button', { name: 'Add Batman' })).toBeInTheDocument();
    expect(within(option('Batman Returns')).getByText('Added by me')).toBeInTheDocument();
    expect(
      within(option('Batman Returns')).getByRole('button', { name: 'Remove Batman Returns' }),
    ).toBeInTheDocument();
    expect(
      within(option('Batman Begins')).getByText('Keywords not fetched yet'),
    ).toBeInTheDocument();
    const noId = within(option('Gremlins'));
    expect(noId.getByText('Needs a match first')).toBeInTheDocument();
    expect(noId.getByRole('button', { name: 'Find the film… Gremlins' })).toBeInTheDocument();
  });

  it('acts on a press, handing over the row and the button', async () => {
    const user = userEvent.setup();
    const { onAct } = setup();
    await user.click(screen.getByRole('button', { name: 'Add Batman' }));
    expect(onAct).toHaveBeenCalledTimes(1);
    expect(onAct.mock.calls[0][0]).toMatchObject({ filmId: 1, state: 'NOT_IN' });
    expect(onAct.mock.calls[0][1]).toBe(screen.getByRole('button', { name: 'Add Batman' }));
  });

  it('blocks a result while a write for its TMDB id is in flight', async () => {
    const user = userEvent.setup();
    const { onAct } = setup({ busy: new Set([101]) });
    const button = screen.getByRole('button', { name: 'Add Batman' });
    expect(button).toHaveAttribute('aria-disabled', 'true');
    await user.click(button);
    expect(onAct).not.toHaveBeenCalled();
  });

  it('says when the states could not be read', () => {
    setup({ statesFailed: true });
    expect(screen.getByRole('status')).toHaveTextContent(
      /Couldn’t check which of these are in Christmas week/,
    );
  });

  it('keeps one status region, so a change of the head text is announced from it', () => {
    const props = {
      rows: [],
      busy: new Set(),
      statesFailed: false,
      onAct: vi.fn(),
      registerButton: vi.fn(() => () => {}),
    };
    const { rerender } = render(<ChristmasAddResults head={SEARCH_LOADING_TEXT} {...props} />);
    const region = screen.getByRole('status');
    expect(region).toHaveTextContent(SEARCH_LOADING_TEXT);
    rerender(<ChristmasAddResults head="2 films in your library" {...props} />);
    expect(screen.getByRole('status')).toBe(region);
    expect(region).toHaveTextContent('2 films in your library');
    rerender(<ChristmasAddResults head={SEARCH_ERROR_TEXT} {...props} statesFailed />);
    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(region).toHaveTextContent(SEARCH_ERROR_TEXT);
  });
});

describe('headText', () => {
  const rows = [row(1, 'A', 'NOT_IN')];
  it('says loading, unavailable, no match, the count, and the cap', () => {
    expect(headText({ status: 'loading', ready: false, query: 'a', rows: [], total: 0 })).toBe(
      SEARCH_LOADING_TEXT,
    );
    expect(headText({ status: 'error', ready: false, query: 'a', rows: [], total: 0 })).toBe(
      SEARCH_ERROR_TEXT,
    );
    expect(headText({ status: 'ready', ready: true, query: ' zz ', rows: [], total: 0 })).toBe(
      'No films in your library match “zz”.',
    );
    expect(headText({ status: 'ready', ready: true, query: 'a', rows, total: 1 })).toBe(
      '1 film in your library',
    );
    expect(headText({ status: 'ready', ready: true, query: 'a', rows, total: 12 })).toBe(
      'Showing 1 of 12 films in your library',
    );
  });
});
