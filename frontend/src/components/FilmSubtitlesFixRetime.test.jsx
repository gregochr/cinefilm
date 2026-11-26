import { useState } from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import FilmSubtitlesFix from './FilmSubtitlesFix.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

const base = fixtures.adminSubtitles[5];
const film = { id: 6, title: 'Blade Runner', year: 1982 };
const SPED_UP = 'Timed for 23.976 fps; this film is 25 fps, so it is sped up 4%';
const DRIFT = 'Not retimed; it may drift';

const download = (over = {}) => ({
  ...base,
  id: 6,
  origin: 'OPENSUBTITLES',
  release: 'Blade.Runner.1982.1080p',
  served: true,
  ownerAttached: false,
  replacedByDownload: false,
  timedFps: 23.976,
  filmFps: 25,
  retime: 'AUTO',
  retimedBy: 0.95904,
  rateLine: SPED_UP,
  retimeAction: 'DONT',
  ...over,
});
const off = (over = {}) =>
  download({ retime: 'OFF', retimedBy: null, rateLine: DRIFT, retimeAction: 'RETIME', ...over });

const owner = {
  ...base,
  id: 7,
  origin: 'FILE',
  path: 'Blade Runner (1982)/Blade Runner.en.forced.srt',
};

/**
 * A server that keeps the list of `rows`. `onRetime()` answers each PUT with the updated row (the list then holds it,
 * as the server's would) or a failing Response, or a promise of either.
 */
function serve(rows, onRetime) {
  const list = [...rows];
  const requests = [];
  installFetch((method, url, init) => {
    if (url === '/api/admin/subtitles') return respond(structuredClone(list));
    const match = url.match(/^\/api\/admin\/subtitles\/(\d+)\/retime$/);
    if (match && method === 'PUT') {
      requests.push({ id: Number(match[1]), body: JSON.parse(init.body) });
      return Promise.resolve(onRetime()).then((answer) => {
        if (answer instanceof Response) return answer;
        list[list.findIndex((r) => r.id === answer.id)] = answer;
        return respond(structuredClone(answer));
      });
    }
    return null;
  });
  return requests;
}

function Host() {
  const [opener, setOpener] = useState(null);
  return (
    <MemoryRouter>
      <button type="button" onClick={(event) => setOpener({ el: event.currentTarget })}>
        Open the fixer
      </button>
      {opener ? (
        <FilmSubtitlesFix film={film} opener={opener.el} onClose={() => setOpener(null)} />
      ) : null}
    </MemoryRouter>
  );
}

async function open() {
  render(<Host />);
  await userEvent.click(screen.getByRole('button', { name: 'Open the fixer' }));
  const dialog = await screen.findByRole('dialog', { name: 'Blade Runner (1982)' });
  await within(dialog).findByRole('list', { name: 'Subtitle files for this film' });
  return dialog;
}

describe('FilmSubtitlesFix: retime', () => {
  it('shows the download’s rate line, and none for the owner’s file, which has no control', async () => {
    serve([download(), { ...owner, rateLine: 'Should never show' }], () => null);
    const dialog = await open();

    expect(within(dialog).getByText(SPED_UP)).toBeInTheDocument();
    expect(within(dialog).queryByText('Should never show')).not.toBeInTheDocument();
    expect(within(dialog).getAllByRole('button', { name: /retime/i })).toHaveLength(1);
  });

  it('sends exactly {retime:false} for the row, then shows the returned rate line and the other label', async () => {
    const requests = serve([download()], () => off());
    const dialog = await open();

    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Don’t retime: Blade.Runner.1982.1080p' }),
    );

    expect(await within(dialog).findByText(DRIFT)).toBeInTheDocument();
    expect(requests).toEqual([{ id: 6, body: { retime: false } }]);
    expect(
      within(dialog).getByRole('button', { name: 'Retime: Blade.Runner.1982.1080p' }),
    ).toBeInTheDocument();
  });

  it('sends exactly {retime:true} from "Retime"', async () => {
    const requests = serve([off()], () => download());
    const dialog = await open();

    await userEvent.click(within(dialog).getByRole('button', { name: /^Retime:/ }));

    expect(await within(dialog).findByText(SPED_UP)).toBeInTheDocument();
    expect(requests).toEqual([{ id: 6, body: { retime: true } }]);
  });

  it('shows the server’s refusal and leaves the row as it was', async () => {
    serve([off()], () => respond({ error: 'The setting is restore' }, 409));
    const dialog = await open();

    await userEvent.click(within(dialog).getByRole('button', { name: /^Retime:/ }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('The setting is restore.');
    expect(within(dialog).getByText(DRIFT)).toBeInTheDocument();
  });

  it('offers no control for a file that fits as it is', async () => {
    serve(
      [
        download({
          retime: 'OFF_FITS',
          retimedBy: null,
          rateLine: 'Fits this copy as it is',
          retimeAction: null,
        }),
      ],
      () => null,
    );
    const dialog = await open();

    expect(within(dialog).getByText('Fits this copy as it is')).toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: /retime/i })).not.toBeInTheDocument();
  });

  it('sends one request for a double click and is aria-disabled while it is in flight', async () => {
    let answer;
    const requests = serve(
      [download()],
      () =>
        new Promise((resolve) => {
          answer = resolve;
        }),
    );
    const dialog = await open();
    const button = within(dialog).getByRole('button', { name: /Don’t retime/ });

    await userEvent.dblClick(button);

    await waitFor(() => expect(button).toHaveAttribute('aria-disabled', 'true'));
    expect(requests).toHaveLength(1);
    answer(off());
    expect(await within(dialog).findByText(DRIFT)).toBeInTheDocument();
    expect(requests).toHaveLength(1);
  });
});
