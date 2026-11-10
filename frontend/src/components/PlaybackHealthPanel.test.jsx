import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import PlaybackHealthPanel from './PlaybackHealthPanel.jsx';

afterEach(() => vi.unstubAllGlobals());

const URL = '/api/admin/playback-health';

async function openPanel() {
  render(<PlaybackHealthPanel />);
  const panel = await screen.findByRole('region', { name: 'Playback health' });
  const list = await within(panel).findByRole('list', { name: 'Recent playbacks' });
  return { panel, list };
}

describe('PlaybackHealthPanel', () => {
  it('shows a loading skeleton while the list loads', () => {
    installFetch(() => new Promise(() => {}));
    render(<PlaybackHealthPanel />);
    const panel = screen.getByRole('region', { name: 'Playback health' });
    expect(within(panel).getByRole('status', { name: 'Loading' })).toBeInTheDocument();
  });

  it('lists each viewing with its title, tag and lines', async () => {
    installFetch();
    const { list } = await openPanel();
    const [train, home, unplayed] = within(list).getAllByRole('listitem');

    expect(within(train).getByText('Heat')).toBeInTheDocument();
    expect(within(train).getByText('Train')).toBeInTheDocument();
    expect(within(train).getByText('10 Nov 19:43 · 1h 12m watched · Mac')).toBeInTheDocument();
    expect(
      within(train).getByText('Start 2.4 s · 2 stalls, 9 s · 1 reconnect'),
    ).toBeInTheDocument();
    expect(
      within(train).getByText('480p 34% · 360p 66% · 0.4 to 2.4 Mbit/s, typically 0.9'),
    ).toBeInTheDocument();
    expect(within(train).getByText('Virgin Media Limited · 48 ms')).toBeInTheDocument();

    expect(within(home).getByText('Blade Runner')).toBeInTheDocument();
    expect(within(home).getByText('Home')).toBeInTheDocument();
    expect(within(home).getByText('09 Nov 18:10 · 1h 51m watched · iPhone')).toBeInTheDocument();
    expect(within(home).getByText('Start 0.9 s · No stalls')).toBeInTheDocument();
    expect(within(home).getByText('Original 100% · no speed samples')).toBeInTheDocument();
    expect(within(home).getByText('4 ms')).toBeInTheDocument();

    expect(within(unplayed).getByText('The Wicker Man')).toBeInTheDocument();
    expect(within(unplayed).getByText('Unknown')).toBeInTheDocument();
    expect(within(unplayed).getByText('Not played yet · No stalls')).toBeInTheDocument();
  });

  it('renders no empty element for a line that has nothing to say', async () => {
    installFetch();
    const { list } = await openPanel();
    const unplayed = within(list).getAllByRole('listitem')[2];
    expect(unplayed).toHaveTextContent(
      'The Wicker ManUnknown08 Nov 21:15 · under 1m watched · WindowsNot played yet · No stallsFull record',
    );
    expect(within(unplayed).queryByText(/Mbit\/s/)).not.toBeInTheDocument();
    expect(within(unplayed).queryByText(/no speed samples/)).not.toBeInTheDocument();
    expect(within(unplayed).queryByText(/ ms$/)).not.toBeInTheDocument();
  });

  it('links each row to its full record in a new tab, named after the film', async () => {
    installFetch();
    const { list } = await openPanel();
    const links = within(list).getAllByRole('link');
    expect(links.map((l) => l.getAttribute('aria-label'))).toEqual([
      'Full record of Heat',
      'Full record of Blade Runner',
      'Full record of The Wicker Man',
    ]);
    const heat = within(list).getByRole('link', { name: 'Full record of Heat' });
    expect(heat).toHaveTextContent('Full record');
    expect(heat).toHaveAttribute(
      'href',
      '/api/admin/playback-health/3f6c1d2e-8a4b-4c7e-9b15-2d0e6f7a1c34',
    );
    expect(heat).toHaveAttribute('target', '_blank');
    expect(heat).toHaveAttribute('rel', 'noreferrer');
  });

  it('shows what the report says as text, never as markup', async () => {
    const [first] = fixtures.adminPlaybackHealth.playbacks;
    installFetch((m, url) =>
      url === URL
        ? respond({
            playbacks: [
              {
                ...first,
                filmTitle: '<img src=x onerror=alert(1)>',
                report: { ...first.report, operator: 'AS1 <b>bold</b> Net' },
              },
            ],
          })
        : null,
    );
    const { list } = await openPanel();
    expect(within(list).getByText('<img src=x onerror=alert(1)>')).toBeInTheDocument();
    expect(within(list).getByText('<b>bold</b> Net · 48 ms')).toBeInTheDocument();
    expect(within(list).queryByRole('img')).not.toBeInTheDocument();
  });

  it('says so when nothing has been recorded', async () => {
    installFetch((m, url) => (url === URL ? respond({ playbacks: [] }) : null));
    render(<PlaybackHealthPanel />);
    const panel = await screen.findByRole('region', { name: 'Playback health' });
    expect(await within(panel).findByText('No playbacks recorded yet.')).toBeInTheDocument();
    expect(within(panel).queryByRole('list')).not.toBeInTheDocument();
  });

  it('announces a failure and Try again reads the list again', async () => {
    let fail = true;
    const fetchMock = installFetch((m, url) => (url === URL && fail ? respond(null, 500) : null));
    render(<PlaybackHealthPanel />);
    const panel = await screen.findByRole('region', { name: 'Playback health' });
    const alert = await within(panel).findByRole('alert');
    expect(alert).toHaveTextContent('The projector jammed');
    expect(alert).toHaveTextContent('Couldn’t load playback health.');
    expect(fetchMock).toHaveBeenCalledTimes(1);

    fail = false;
    await userEvent.click(within(panel).getByRole('button', { name: 'Try again' }));
    expect(
      await within(panel).findByRole('list', { name: 'Recent playbacks' }),
    ).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(within(panel).queryByRole('alert')).not.toBeInTheDocument();
  });

  it('swaps Try again for the skeleton at once, so it cannot start a second request while loading', async () => {
    let calls = 0;
    let release;
    const fetchMock = installFetch((m, url) => {
      if (url !== URL) return null;
      calls += 1;
      if (calls === 1) return respond(null, 500);
      return new Promise((resolve) => {
        release = () => resolve(respond(fixtures.adminPlaybackHealth));
      });
    });
    render(<PlaybackHealthPanel />);
    await userEvent.click(await screen.findByRole('button', { name: 'Try again' }));
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
    release();
    expect(await screen.findByRole('list', { name: 'Recent playbacks' })).toBeInTheDocument();
    expect(fetchMock.mock.calls.filter((c) => c[0] === URL)).toHaveLength(2);
    expect(screen.getAllByRole('listitem')).toHaveLength(3);
  });
});
