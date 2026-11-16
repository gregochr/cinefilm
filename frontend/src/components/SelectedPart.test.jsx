import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import SelectedPart from './SelectedPart.jsx';

afterEach(() => vi.unstubAllGlobals());

const ID = '323111c2-0000-4000-8000-000000000002';
const part = {
  playbackId: ID,
  startedAt: '2026-11-12T17:56:53.956Z',
  endedAt: '2026-11-12T20:33:53.080Z',
  watchedMs: 6_360_000,
  environment: 'train',
};
const full = (over = {}) => {
  const record = structuredClone(fixtures.adminPlaybackRecord);
  record.playbackId = ID;
  Object.assign(record.report, {
    startupMs: 6300,
    stallCount: 155,
    stallMs: 2_011_000,
    watchedMs: 6_360_000,
    reconnectCount: 0,
    fellBack: false,
    operator: 'AS5089 Virgin Media Limited',
    environment: 'train',
    ...over,
  });
  return record;
};
/** Serves `answer` for this part's record only: any other id fails the test by answering 500. */
const serve = (answer) =>
  installFetch((m, url) => (url.endsWith(ID) ? answer() : respond({ error: 'other' }, 500)));

describe('SelectedPart', () => {
  it('shows a skeleton while loading', () => {
    serve(() => new Promise(() => {}));
    render(<SelectedPart part={part} film="The Rock" />);
    expect(
      screen.getByRole('status', { name: 'Loading the minute by minute view' }),
    ).toBeInTheDocument();
  });

  it('shows the strip, and the part’s times at the front of its figures line, from its own record', async () => {
    serve(() => respond(full()));
    render(<SelectedPart part={part} film="The Rock" />);
    expect(
      await screen.findByText('17:56–20:33 · Start 6.3 s · 155 stalls, 33m 31s'),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('img', { name: 'Speed and quality minute by minute for The Rock' }),
    ).toBeInTheDocument();
  });

  it('names its controls by its times when the viewing has several parts, and plainly when it has one', async () => {
    serve(() => new Promise(() => {}));
    const { unmount } = render(<SelectedPart part={part} film="The Rock" named />);
    expect(
      screen.getByRole('status', { name: 'Loading the minute by minute view, 17:56–20:33' }),
    ).toBeInTheDocument();
    unmount();
    serve(() => respond(full()));
    render(<SelectedPart part={part} film="The Rock" named />);
    expect(
      await screen.findByRole('img', {
        name: 'Speed and quality minute by minute for The Rock, 17:56–20:33',
      }),
    ).toBeInTheDocument();
  });

  it('says there are no minutes for a record that is gone (404), with no retry', async () => {
    serve(() => respond({ error: 'No such playback' }, 404));
    render(<SelectedPart part={part} film="The Rock" />);
    expect(
      await screen.findByText('No minute by minute figures for this session.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Try again/ })).not.toBeInTheDocument();
  });

  it('shows an error whose retry reads this part again, named by its times when asked', async () => {
    const user = userEvent.setup();
    let tries = 0;
    serve(() => {
      tries += 1;
      return tries === 1 ? respond({ error: 'x' }, 500) : respond(full());
    });
    render(<SelectedPart part={part} film="The Rock" named />);
    const alert = await screen.findByRole('alert', {
      name: 'Couldn’t load the minutes for 17:56–20:33.',
    });
    await user.click(within(alert).getByRole('button', { name: 'Try again, 17:56–20:33' }));
    expect(await screen.findByText(/^17:56–20:33 · Start 6\.3 s/)).toBeInTheDocument();
    expect(tries).toBe(2);
  });

  it('leaves the alert and its button plainly named when the viewing has one part', async () => {
    serve(() => respond({ error: 'x' }, 500));
    render(<SelectedPart part={part} film="The Rock" />);
    expect(await screen.findByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  describe('onRecord', () => {
    it('is not called while loading', () => {
      const onRecord = vi.fn();
      serve(() => new Promise(() => {}));
      render(<SelectedPart part={part} film="The Rock" onRecord={onRecord} />);
      expect(onRecord).not.toHaveBeenCalled();
    });

    it('is not called on an error', async () => {
      const onRecord = vi.fn();
      serve(() => respond({ error: 'x' }, 500));
      render(<SelectedPart part={part} film="The Rock" onRecord={onRecord} />);
      await screen.findByRole('alert');
      expect(onRecord).not.toHaveBeenCalled();
    });

    it('is called once with the record when it arrives', async () => {
      const onRecord = vi.fn();
      serve(() => respond(full()));
      render(<SelectedPart part={part} film="The Rock" onRecord={onRecord} />);
      await screen.findByText(/^17:56–20:33 · /);
      expect(onRecord).toHaveBeenCalledTimes(1);
      expect(onRecord).toHaveBeenCalledWith(expect.objectContaining({ playbackId: ID }));
    });
  });

  describe('the place', () => {
    const first = { environment: 'home', operator: 'Virgin Media Limited' };

    it('leads the figures line when it differs from the first part’s', async () => {
      serve(() => respond(full({ operator: 'AS12576 EE Limited' })));
      render(<SelectedPart part={part} film="The Rock" first={first} />);
      expect(
        await screen.findByText(
          'Train (EE Limited) · 17:56–20:33 · Start 6.3 s · 155 stalls, 33m 31s',
        ),
      ).toBeInTheDocument();
    });

    it('is left out when the part is on the same place as the first', async () => {
      serve(() => respond(full()));
      render(
        <SelectedPart
          part={part}
          film="The Rock"
          first={{ environment: 'train', operator: 'Virgin Media Limited' }}
        />,
      );
      expect(
        await screen.findByText('17:56–20:33 · Start 6.3 s · 155 stalls, 33m 31s'),
      ).toBeInTheDocument();
    });

    it('shows the place alone when only the environment differs and no operator is known', async () => {
      serve(() => respond(full({ operator: null })));
      render(
        <SelectedPart
          part={part}
          film="The Rock"
          first={{ environment: 'home', operator: null }}
        />,
      );
      expect(await screen.findByText(/^Train · 17:56–20:33 · /)).toBeInTheDocument();
    });
  });
});
