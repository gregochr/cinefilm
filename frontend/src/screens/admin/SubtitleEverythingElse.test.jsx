import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch } from '../../test/mockApi.jsx';
import { fullRow } from '../../test/needRoutes.js';
import SubtitleEverythingElse, { Rich } from './SubtitleEverythingElse.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

const playing = (over = {}) =>
  fullRow({
    filmId: 3,
    title: 'Das Boot',
    year: 1981,
    unresolved: false,
    fullSubtitle: { source: 'SIDECAR' },
    ...over,
  });

const figures = (over = {}) => ({
  films: [playing()],
  haveSubtitles: 1,
  inEnglish: 1341,
  beingChecked: 2,
  ...over,
});

const needState = (rows = []) => ({
  rows,
  saving: {},
  problems: {},
  choose: vi.fn(() => Promise.resolve()),
});
const NEEDS = { full: needState(), forced: needState() };

function renderElse(props = {}) {
  installFetch();
  return render(
    <MemoryRouter>
      <SubtitleEverythingElse
        figures={figures()}
        needs={NEEDS}
        scan={fixtures.adminScan}
        version="v1"
        onChanged={vi.fn()}
        {...props}
      />
    </MemoryRouter>,
  );
}

describe('SubtitleEverythingElse', () => {
  it('shows each figure with its label, the English-formatted number and the search status', () => {
    renderElse();

    const list = screen.getByRole('list', { name: 'Everything else' });
    expect(within(list).getByText('1')).toBeInTheDocument();
    expect(within(list).getByText('foreign film has English subtitles')).toBeInTheDocument();
    expect(within(list).getByText('1,341')).toBeInTheDocument();
    expect(within(list).getByText('films are in English')).toBeInTheDocument();
    expect(within(list).getByText('2')).toBeInTheDocument();
    expect(within(list).getByText('being checked online now')).toBeInTheDocument();
    expect(screen.getByText('Subtitles searched 12 Nov 06:00')).toBeInTheDocument();
  });

  it('uses the plural forms for other counts', () => {
    renderElse({ figures: figures({ haveSubtitles: 63, inEnglish: 1 }) });

    expect(screen.getByText('foreign films have English subtitles')).toBeInTheDocument();
    expect(screen.getByText('film is in English')).toBeInTheDocument();
  });

  it('draws only the figures that are known, and no films button for none', () => {
    renderElse({
      figures: { films: null, haveSubtitles: null, inEnglish: null, beingChecked: null },
    });

    expect(screen.queryByRole('listitem')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Show the/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Show all subtitle files' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Everything else' })).toBeInTheDocument();
  });

  it('draws a zero, not nothing, and offers no films list for it', () => {
    renderElse({ figures: figures({ films: [], haveSubtitles: 0, beingChecked: 0 }) });

    expect(screen.getAllByText('0')).toHaveLength(2);
    expect(screen.queryByRole('button', { name: /Show the/ })).not.toBeInTheDocument();
  });

  it('toggles the read-only list of films with its button state', async () => {
    renderElse();
    const toggle = screen.getByRole('button', { name: 'Show the 1 film' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(toggle).not.toHaveAttribute('aria-controls');

    await userEvent.click(toggle);

    const films = screen.getByRole('list', { name: 'Foreign films with English subtitles' });
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(toggle).toHaveAttribute('aria-controls', films.id);
    expect(within(films).getByText('Das Boot (1981)')).toBeInTheDocument();
    expect(within(films).getByText('· Playing: your file')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Hide the 1 film' })).toBe(toggle);
  });

  it('keeps the file list closed, and unread, until asked', async () => {
    const fetchMock = installFetch();
    render(
      <MemoryRouter>
        <SubtitleEverythingElse
          figures={figures()}
          needs={NEEDS}
          scan={fixtures.adminScan}
          version="v1"
          onChanged={vi.fn()}
        />
      </MemoryRouter>,
    );
    expect(fetchMock.mock.calls.filter(([url]) => url === '/api/admin/subtitles')).toHaveLength(0);

    await userEvent.click(screen.getByRole('button', { name: 'Show all subtitle files' }));

    const files = await screen.findByRole('region', { name: 'Subtitle files' });
    expect(await within(files).findByText('Heat (1995)/Heat.en.forced.srt')).toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([url]) => url === '/api/admin/subtitles')).toHaveLength(1);
  });

  it('ends with the note that points to the film page and the player', () => {
    renderElse();

    expect(screen.getByText('Subtitles → Fix…').tagName).toBe('B');
    expect(
      screen.getByText(/or in the player — that’s where timing, replacing a file/),
    ).toBeInTheDocument();
  });
});

describe('SubtitleEverythingElse: Your choices', () => {
  it('shows the toggle only while the lists hold a choice of the owner’s', () => {
    const { rerender } = renderElse();
    expect(screen.queryByRole('button', { name: /^Your choices/ })).not.toBeInTheDocument();

    const chosen = fullRow({ ownerChoice: 'NOT_NEEDED' });
    rerender(
      <MemoryRouter>
        <SubtitleEverythingElse
          figures={figures()}
          needs={{ full: needState([chosen]), forced: needState() }}
          scan={fixtures.adminScan}
          version="v1"
          onChanged={vi.fn()}
        />
      </MemoryRouter>,
    );

    expect(screen.getByRole('button', { name: 'Your choices (1)' })).toBeInTheDocument();
  });
});

describe('Rich', () => {
  it('draws bold parts as <b> and the rest as plain text', () => {
    render(
      <p>
        <Rich parts={[{ text: 'one ' }, { text: 'two', bold: true }]} />
      </p>,
    );

    expect(screen.getByText('two').tagName).toBe('B');
    expect(screen.getByText('one').tagName).toBe('SPAN');
  });
});
