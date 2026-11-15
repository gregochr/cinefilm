import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import SubtitlesPanel, { FILES_PAGE } from './SubtitlesPanel.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

const files = (n, over = () => ({})) =>
  Array.from({ length: n }, (_, i) => ({
    ...structuredClone(fixtures.adminSubtitles[0]),
    id: i + 1,
    path: `Film ${String(i + 1).padStart(3, '0')}/File.en.forced.srt`,
    ...over(i),
  }));

function renderPanel(items, props = {}) {
  installFetch((method, url) => (url === '/api/admin/subtitles' ? respond(items) : null));
  return render(
    <MemoryRouter>
      <SubtitlesPanel version="v" onChanged={vi.fn()} {...props} />
    </MemoryRouter>,
  );
}

const listed = () => screen.queryAllByRole('button', { name: /Show the cues of/ });

describe('SubtitlesPanel: pagination', () => {
  it('pages at 50', () => {
    expect(FILES_PAGE).toBe(50);
  });

  it('lists 49 files with no "Show more"', async () => {
    renderPanel(files(49));

    await screen.findByText('Film 001/File.en.forced.srt');

    expect(listed()).toHaveLength(49);
    expect(screen.queryByRole('button', { name: /Show more/ })).not.toBeInTheDocument();
  });

  it('lists exactly 50 files with no "Show more"', async () => {
    renderPanel(files(50));

    await screen.findByText('Film 050/File.en.forced.srt');

    expect(listed()).toHaveLength(50);
    expect(screen.queryByRole('button', { name: /Show more/ })).not.toBeInTheDocument();
  });

  it('lists 50 of 51 files and "Show more (1 left)" brings the last', async () => {
    renderPanel(files(51));
    await screen.findByText('Film 050/File.en.forced.srt');

    expect(listed()).toHaveLength(50);
    expect(screen.queryByText('Film 051/File.en.forced.srt')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Show more (1 left)' }));

    expect(screen.getByText('Film 051/File.en.forced.srt')).toBeInTheDocument();
    expect(listed()).toHaveLength(51);
    expect(screen.queryByRole('button', { name: /Show more/ })).not.toBeInTheDocument();
  });

  it('groups before it cuts: file 51 of 51, unmatched, is on the first page under Unmatched', async () => {
    const items = files(51, (i) => (i === 50 ? { state: 'unmatched', filmId: null } : {}));
    renderPanel(items);
    await screen.findByText('Film 051/File.en.forced.srt');

    expect(listed()).toHaveLength(50);
    const unmatched = screen.getByRole('list', { name: 'Unmatched subtitles' });
    expect(within(unmatched).getByText('Film 051/File.en.forced.srt')).toBeInTheDocument();
    // The 50th matched file (the 49th in server order) is the last on the page; the 50th in server order waits.
    expect(screen.getByText('Film 049/File.en.forced.srt')).toBeInTheDocument();
    expect(screen.queryByText('Film 050/File.en.forced.srt')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Show more (1 left)' }));
    expect(screen.getByText('Film 050/File.en.forced.srt')).toBeInTheDocument();
  });

  it('counts what is left per page, and a chip starts again from the first page', async () => {
    renderPanel(files(120, (i) => ({ kind: i % 2 === 0 ? 'FORCED' : 'FULL' })));
    await screen.findByText('Film 001/File.en.forced.srt');

    expect(screen.getByRole('button', { name: 'Show more (70 left)' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Show more (70 left)' }));
    expect(listed()).toHaveLength(100);
    expect(screen.getByRole('button', { name: 'Show more (20 left)' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /^Full \(60\)$/ }));
    expect(listed()).toHaveLength(50);
    expect(screen.getByRole('button', { name: 'Show more (10 left)' })).toBeInTheDocument();
  });
});

describe('SubtitlesPanel: the card', () => {
  it('is the "Subtitle files" region, and holds the file chips and the per-file tools', async () => {
    renderPanel(files(1));

    const region = await screen.findByRole('region', { name: 'Subtitle files' });
    expect(within(region).getByRole('group', { name: 'Show subtitle files' })).toBeInTheDocument();
    expect(
      await within(region).findByRole('button', { name: /Looks right|Show the cues/ }),
    ).toBeInTheDocument();
    expect(
      within(region).getByRole('button', { name: /^Attach .* to a film|^Move .*/ }),
    ).toBeInTheDocument();
  });

  it('no longer holds the need lists or the filter chips of the old section', async () => {
    renderPanel(files(1));

    await screen.findByRole('region', { name: 'Subtitle files' });

    expect(screen.queryByRole('heading', { name: 'Full subtitles' })).not.toBeInTheDocument();
    expect(
      screen.queryByRole('heading', { name: 'Which films need them' }),
    ).not.toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Show full subtitles' })).not.toBeInTheDocument();
  });

  it('calls onChanged after a file tool saved something', async () => {
    const onChanged = vi.fn();
    installFetch((method, url) => {
      if (url === '/api/admin/subtitles') return respond(files(1));
      if (url === '/api/admin/subtitles/1/verify') {
        return respond({ ...files(1)[0], verified: true });
      }
      return null;
    });
    render(
      <MemoryRouter>
        <SubtitlesPanel version="v" onChanged={onChanged} />
      </MemoryRouter>,
    );
    await userEvent.click(await screen.findByRole('button', { name: /Show the cues of/ }));

    await userEvent.click(await screen.findByRole('button', { name: 'Looks right' }));

    await vi.waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
  });
});
