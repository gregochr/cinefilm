import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, renderApp, respond } from '../../test/mockApi.jsx';
import JourneyList from './JourneyList.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

const PATH = '/admin/playback/journeys';
const stored = (over = {}) => ({
  id: 12,
  runStartedMs: 1898891940000,
  segment: 0,
  trainUnit: 'Unit-001',
  travelClass: 'standard',
  osVersion: '15.1',
  loggerVersion: 3,
  sourceFile: 'train-feed-example.jsonl',
  importedAt: '2030-03-05T09:00:00Z',
  device: { model: 'ExampleBook1,1', cpu: 'Example chip', cores: 8, threads: 8, memoryBytes: 1 },
  samples: 363,
  from: '2030-03-04T22:00:00Z',
  to: '2030-03-05T00:47:30Z',
  linked: 2,
  train: { uid: 'X12345', date: '2030-03-04', headcode: '9Z99' },
  ...over,
});
const NEWER = stored({
  id: 20,
  runStartedMs: 1899000000000,
  trainUnit: null,
  travelClass: null,
  sourceFile: null,
  device: null,
  samples: 1,
  linked: 0,
  from: null,
  to: null,
  train: null,
});
const isRead = (method, url) => method === 'GET' && url === '/api/admin/journeys';
const IDLE = {
  openId: null,
  busy: false,
  failure: null,
  onOpen: () => {},
  onKeep: () => {},
  onConfirm: () => {},
};

describe('JourneyList', () => {
  it('shows a labelled static skeleton before the first answer', () => {
    render(
      <JourneyList
        journeys={null}
        loading={false}
        error={null}
        onRetry={() => {}}
        deleting={IDLE}
      />,
    );

    expect(screen.getByRole('status', { name: 'Loading journeys' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Stored journeys' })).toBeInTheDocument();
  });

  it('says one sentence when nothing is stored', () => {
    render(
      <JourneyList journeys={[]} loading={false} error={null} onRetry={() => {}} deleting={IDLE} />,
    );

    expect(screen.getByText('No journeys yet. Import a log above.')).toBeInTheDocument();
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
  });

  it('lists the rows in the given order with every line of each', () => {
    render(
      <JourneyList
        journeys={[NEWER, stored()]}
        loading={false}
        error={null}
        onRetry={() => {}}
        deleting={IDLE}
      />,
    );

    const rows = within(screen.getByRole('list', { name: 'Stored journeys' })).getAllByRole(
      'listitem',
    );
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent(
      'No samplesUnit not named · 1 round · no viewingsNo train yet',
    );
    expect(rows[1]).toHaveTextContent(
      '4 Mar 2030, 22:00 to 5 Mar 2030, 00:47' +
        'Unit-001 · Standard · 363 rounds · 2 viewings' +
        'Train 9Z99 on 4 Mar 2030' +
        'train-feed-example.jsonl · ExampleBook1,1',
    );
  });

  it('shows the failure with its retry in place of the rows, and calls the retry', async () => {
    const onRetry = vi.fn();
    render(
      <JourneyList
        journeys={[stored()]}
        loading={false}
        error={new Error('x')}
        onRetry={onRetry}
        deleting={IDLE}
      />,
    );

    expect(screen.getByRole('alert')).toHaveTextContent('Couldn’t load the journeys.');
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Check again' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});

describe('JourneyList deleting', () => {
  const rowOf = (journeys, deleting) => {
    render(
      <JourneyList
        journeys={journeys}
        loading={false}
        error={null}
        onRetry={() => {}}
        deleting={{ ...IDLE, ...deleting }}
      />,
    );
    return within(screen.getByRole('list', { name: 'Stored journeys' })).getAllByRole('listitem');
  };

  it('gives each row its own Delete… named by the row', () => {
    const rows = rowOf([NEWER, stored()]);

    expect(
      within(rows[0]).getByRole('button', { name: 'Delete the journey with no samples' }),
    ).toHaveTextContent('Delete…');
    expect(
      within(rows[1]).getByRole('button', {
        name: 'Delete the journey logged 4 Mar 2030, 22:00 to 5 Mar 2030, 00:47',
      }),
    ).toHaveTextContent('Delete…');
  });

  it('tells the page which journey was pressed', async () => {
    const onOpen = vi.fn();
    const rows = rowOf([NEWER, stored()], { onOpen });

    await userEvent.click(within(rows[1]).getByRole('button', { name: /^Delete the journey/ }));

    expect(onOpen).toHaveBeenCalledExactlyOnceWith(12);
  });

  it('opens the confirmation in the row whose id is open and in no other', () => {
    const rows = rowOf([NEWER, stored()], { openId: 12 });

    expect(within(rows[1]).getByRole('group', { name: 'Delete this journey?' })).toHaveTextContent(
      'Delete this journey and its 363 rounds? The log on the laptop is not touched.',
    );
    expect(within(rows[0]).queryByRole('group')).not.toBeInTheDocument();
  });

  it('tells the page which journey is to go, and that it is kept', async () => {
    const onConfirm = vi.fn();
    const onKeep = vi.fn();
    const rows = rowOf([NEWER, stored()], { openId: 12, onConfirm, onKeep });

    await userEvent.click(within(rows[1]).getByRole('button', { name: 'Delete journey' }));
    await userEvent.click(within(rows[1]).getByRole('button', { name: 'Keep' }));

    expect(onConfirm).toHaveBeenCalledExactlyOnceWith(12);
    expect(onKeep).toHaveBeenCalledTimes(1);
  });

  it('shows a refusal in the row it was for only', () => {
    const rows = rowOf([NEWER, stored()], {
      openId: 12,
      failure: { id: 20, kind: 'alert', text: 'Couldn’t delete the journey. Try again.' },
    });

    expect(within(rows[1]).queryByRole('alert')).not.toBeInTheDocument();
    expect(within(rows[0]).queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows a refusal in the open group of its own row', () => {
    const rows = rowOf([NEWER, stored()], {
      openId: 12,
      failure: { id: 12, kind: 'alert', text: 'Couldn’t delete the journey. Try again.' },
    });

    expect(within(rows[1]).getByRole('alert')).toHaveTextContent(
      'Couldn’t delete the journey. Try again.',
    );
  });
});

describe('JourneyList retrying', () => {
  const FAILED = new Error('x');
  const view = (journeys, loading, error) => (
    <JourneyList
      journeys={journeys}
      loading={loading}
      error={error}
      onRetry={() => {}}
      deleting={IDLE}
    />
  );
  /** Rows shown, a read again failed, "Check again" pressed and its read now under way. */
  const retrying = async () => {
    const utils = render(view([stored()], false, FAILED));
    await userEvent.click(screen.getByRole('button', { name: 'Check again' }));
    utils.rerender(view([stored()], true, null));
    return utils;
  };

  it('keeps the banner, busy and focused, and hides the old rows while a retry reads', async () => {
    await retrying();

    const button = screen.getByRole('button', { name: 'Check again' });
    expect(button).toHaveFocus();
    expect(button).toHaveAttribute('aria-disabled', 'true');
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
  });

  it('keeps the banner and the focus when the retry fails', async () => {
    const { rerender } = await retrying();
    const button = screen.getByRole('button', { name: 'Check again' });
    const sentence = screen.getByText('Couldn’t load the journeys.');

    rerender(view([stored()], false, FAILED));

    expect(screen.getByText('Couldn’t load the journeys.')).not.toBe(sentence);
    expect(screen.getByRole('button', { name: 'Check again' })).toBe(button);
    expect(button).toHaveFocus();
    expect(button).not.toHaveAttribute('aria-disabled');
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
  });

  it('shows the rows and focuses the heading when the retry succeeds', async () => {
    const { rerender } = await retrying();

    rerender(view([stored()], false, null));

    expect(screen.getByRole('list', { name: 'Stored journeys' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Stored journeys' })).toHaveFocus();
  });

  it('does not take focus when a read that was not a retry ends', () => {
    const { rerender } = render(view([stored()], false, null));

    rerender(view([stored()], true, null));
    rerender(view([stored()], false, null));

    expect(screen.getByRole('heading', { level: 2, name: 'Stored journeys' })).not.toHaveFocus();
  });
});

describe('JourneyList on its page', () => {
  it('holds the skeleton while the first read is open, then shows the rows', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    installFetch((method, url) => (isRead(method, url) ? gate : null));
    renderApp(PATH);

    expect(screen.getByRole('status', { name: 'Loading journeys' })).toBeInTheDocument();
    await act(async () => release(respond({ journeys: [stored()] })));

    expect(await screen.findByRole('list', { name: 'Stored journeys' })).toBeInTheDocument();
    expect(screen.queryByRole('status', { name: 'Loading journeys' })).not.toBeInTheDocument();
  });

  it('shows the failure, and the retry then shows the rows', async () => {
    let answers = 0;
    installFetch((method, url) => {
      if (!isRead(method, url)) return null;
      answers += 1;
      return answers === 1 ? respond({ error: 'x' }, 500) : respond({ journeys: [stored()] });
    });
    renderApp(PATH);

    expect(await screen.findByText('Couldn’t load the journeys.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Check again' }));

    expect(await screen.findByRole('list', { name: 'Stored journeys' })).toBeInTheDocument();
    expect(screen.queryByText('Couldn’t load the journeys.')).not.toBeInTheDocument();
  });
});
