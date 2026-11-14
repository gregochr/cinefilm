import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NETWORKS_KEY } from '../state/networks.js';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { installStorage } from '../test/storage.js';
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

describe('PlaybackHealthPanel: remembered networks', () => {
  const TITLE = 'Remembered networks on this device';
  const VIRGIN = 'AS5089 Virgin Media Limited';
  const RAIL = 'AS64500 Example Rail';
  let storage;
  beforeEach(() => {
    storage = installStorage();
  });
  const remember = (list) => storage.setItem(NETWORKS_KEY, JSON.stringify(list));

  it('shows no heading and no list when nothing is remembered', async () => {
    installFetch();
    await openPanel();
    expect(screen.queryByRole('heading', { name: TITLE })).not.toBeInTheDocument();
    expect(screen.queryByRole('list', { name: TITLE })).not.toBeInTheDocument();
  });

  it('lists the networks newest first, each with the whole name and its tag', async () => {
    remember([
      { operator: VIRGIN, environment: 'away' },
      { operator: RAIL, environment: 'train' },
    ]);
    installFetch();
    await openPanel();
    expect(screen.getByRole('heading', { level: 3, name: TITLE })).toBeInTheDocument();
    const list = screen.getByRole('list', { name: TITLE });
    const [newest, oldest] = within(list).getAllByRole('listitem');
    expect(within(newest).getByText(RAIL)).toBeInTheDocument();
    expect(within(newest).getByText('Train')).toBeInTheDocument();
    expect(within(oldest).getByText(VIRGIN)).toBeInTheDocument();
    expect(within(oldest).getByText('Away')).toBeInTheDocument();
    expect(within(newest).getByRole('button', { name: `Forget ${RAIL}` })).toHaveTextContent(
      'Forget',
    );
    expect(within(oldest).getByRole('button', { name: `Forget ${VIRGIN}` })).toHaveTextContent(
      'Forget',
    );
    expect(
      screen.getByText(
        "Forget makes CineFilm ask where you're watching next time on that network.",
      ),
    ).toBeInTheDocument();
  });

  it('Forget removes the row and the stored answer, keeps the other, and moves focus to the card heading', async () => {
    remember([
      { operator: VIRGIN, environment: 'away' },
      { operator: RAIL, environment: 'train' },
    ]);
    installFetch();
    await openPanel();
    await userEvent.click(screen.getByRole('button', { name: `Forget ${RAIL}` }));

    const list = screen.getByRole('list', { name: TITLE });
    expect(within(list).getAllByRole('listitem')).toHaveLength(1);
    expect(within(list).getByText(VIRGIN)).toBeInTheDocument();
    expect(within(list).queryByText(RAIL)).not.toBeInTheDocument();
    expect(JSON.parse(storage.getItem(NETWORKS_KEY))).toEqual([
      { operator: VIRGIN, environment: 'away' },
    ]);
    expect(screen.getByRole('heading', { level: 2, name: 'Playback health' })).toHaveFocus();
  });

  it('takes the heading and the list away when the last one is forgotten', async () => {
    remember([{ operator: RAIL, environment: 'train' }]);
    installFetch();
    await openPanel();
    await userEvent.click(screen.getByRole('button', { name: `Forget ${RAIL}` }));
    expect(screen.queryByRole('heading', { name: TITLE })).not.toBeInTheDocument();
    expect(screen.queryByRole('list', { name: TITLE })).not.toBeInTheDocument();
    expect(JSON.parse(storage.getItem(NETWORKS_KEY))).toEqual([]);
    expect(screen.getByRole('heading', { level: 2, name: 'Playback health' })).toHaveFocus();
  });

  it('shows the list while the playbacks are still loading', () => {
    remember([{ operator: RAIL, environment: 'train' }]);
    installFetch(() => new Promise(() => {}));
    render(<PlaybackHealthPanel />);
    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
    expect(screen.getByRole('list', { name: TITLE })).toBeInTheDocument();
  });

  it('shows the list when the playbacks request fails', async () => {
    remember([{ operator: RAIL, environment: 'train' }]);
    installFetch((m, url) => (url === URL ? respond(null, 500) : null));
    render(<PlaybackHealthPanel />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Couldn’t load playback health.');
    expect(screen.getByRole('list', { name: TITLE })).toBeInTheDocument();
    expect(screen.getByText(RAIL)).toBeInTheDocument();
  });

  it('shows the list when there are no playbacks', async () => {
    remember([{ operator: RAIL, environment: 'away' }]);
    installFetch((m, url) => (url === URL ? respond({ playbacks: [] }) : null));
    render(<PlaybackHealthPanel />);
    expect(await screen.findByText('No playbacks recorded yet.')).toBeInTheDocument();
    expect(screen.getByRole('list', { name: TITLE })).toBeInTheDocument();
  });

  it('shows the whole name as text, never as markup', async () => {
    remember([{ operator: 'AS1 <b>bold</b> Net', environment: 'away' }]);
    installFetch();
    await openPanel();
    expect(screen.getByText('AS1 <b>bold</b> Net')).toBeInTheDocument();
  });
});
