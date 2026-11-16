import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, respond } from '../test/mockApi.jsx';
import SubtitleCueList from './SubtitleCueList.jsx';

afterEach(() => vi.unstubAllGlobals());

const item = {
  id: 3,
  path: 'Taken (2008)/Taken.en.forced.srt',
  filmId: 2,
  editionId: 21,
  whole: false,
  part: 1,
  offsetMs: 0,
  version: 1,
};

const show = (over = {}) =>
  render(
    <MemoryRouter>
      <SubtitleCueList item={{ ...item, ...over }} id="cues" />
    </MemoryRouter>,
  );

const cuesRoute = (answer) => (method, url) =>
  url === '/api/admin/subtitles/3/cues' ? answer() : null;

describe('SubtitleCueList', () => {
  it('names a download’s list by its release, never by its made-up path', async () => {
    installFetch(
      cuesRoute(() => respond({ offsetMs: 0, cues: [{ startMs: 1000, endMs: 2000, text: 'Hi' }] })),
    );
    show({
      path: 'opensubtitles:12:345',
      origin: 'OPENSUBTITLES',
      release: 'Taken.2008.1080p.BluRay',
    });

    expect(
      await screen.findByRole('list', { name: 'Cues in Taken.2008.1080p.BluRay' }),
    ).toBeInTheDocument();
  });

  it('calls a download with no release name "download"', async () => {
    installFetch(
      cuesRoute(() => respond({ offsetMs: 0, cues: [{ startMs: 1000, endMs: 2000, text: 'Hi' }] })),
    );
    show({ path: 'opensubtitles:12:345', origin: 'OPENSUBTITLES', release: null });

    expect(await screen.findByRole('list', { name: 'Cues in download' })).toBeInTheDocument();
  });

  it('shows a skeleton while the cues load, then each cue with its clock and a Play from here link', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    installFetch(
      cuesRoute(() =>
        gate.then(() =>
          respond({
            offsetMs: 0,
            cues: [{ startMs: 2196000, endMs: 2198000, text: '<i>Run</i>\nnow' }],
          }),
        ),
      ),
    );
    show();

    expect(screen.getByRole('status', { name: 'Loading cues' })).toBeInTheDocument();
    release();

    expect(await screen.findByText('00:36:36')).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Cues in Taken.en.forced.srt' })).toBeInTheDocument();
    expect(screen.queryByText('Run')).not.toBeInTheDocument();
    // The player opens 3 seconds before the cue.
    expect(screen.getByRole('link', { name: 'Play from here: <i>Run</i> now' })).toHaveAttribute(
      'href',
      '/films/2/play?edition=21&at=2193000',
    );
  });

  it('leaves out the link for a file that is not attached to a film', async () => {
    installFetch(
      cuesRoute(() =>
        respond({ offsetMs: 0, cues: [{ startMs: 1000, endMs: 2000, text: 'Hello' }] }),
      ),
    );
    show({ filmId: null });

    expect(await screen.findByText('Hello')).toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('says so when the file has no cues', async () => {
    installFetch(cuesRoute(() => respond({ offsetMs: 0, cues: [] })));
    show();

    expect(await screen.findByText('This file has no cues.')).toBeInTheDocument();
  });

  it('shows an error with a retry that reads again', async () => {
    let fail = true;
    installFetch(
      cuesRoute(() =>
        fail
          ? respond({ error: 'x' }, 500)
          : respond({ offsetMs: 0, cues: [{ startMs: 1000, endMs: 2000, text: 'Back' }] }),
      ),
    );
    show();

    expect(await screen.findByText('Couldn’t load the cues.')).toBeInTheDocument();
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByText('Back')).toBeInTheDocument();
  });
});
