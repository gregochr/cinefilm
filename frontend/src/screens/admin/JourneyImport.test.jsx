import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { readJourneyLog } from '../../state/journeyFile.js';
import { installFetch, renderApp, respond } from '../../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

const PATH = '/admin/playback/journeys';
const HINT = 'The values left out were out of range or of the wrong kind. They are not sent.';
const EXAMPLE = readFileSync(
  resolve(import.meta.dirname, '../../test/fixtures/journey-log-example.jsonl'),
  'utf8',
);
const CREATED = { journeys: [{ samples: 6, linked: 2 }] };
const T0 = 1790000000000;
const START = { event: 'start', tool: 'train-feed-logger', version: 3, t: T0 };
const lines = (...objects) => objects.map((o) => JSON.stringify(o)).join('\n');
const round = (n) => ({ t: T0 + n * 30000, round: n });

const logFile = (text, name = 'example-log.jsonl') => new File([text], name);
const field = () => screen.getByLabelText('Logger file');
const choose = (text, name) => userEvent.upload(field(), logFile(text, name));
const notification = () => screen.getByRole('status', { name: 'Notification' });
const importButton = () => screen.getByRole('button', { name: /^Import/ });
const imports = (fetchMock) =>
  fetchMock.mock.calls.filter(
    ([url, init]) => url === '/api/admin/journeys' && init?.method === 'POST',
  );
const held = () => {
  let release;
  const gate = new Promise((resolveGate) => {
    release = resolveGate;
  });
  return { gate, release };
};
/** A server that answers the import with `answer` (a Response, or a promise of one). */
const serving = (answer) =>
  installFetch((method, url) =>
    method === 'POST' && url === '/api/admin/journeys' ? answer() : null,
  );

/** A file with `count` rounds of sixteen modems each: its request is over the size one import may be. */
function oversizedLog() {
  const fat = (n) => ({
    ...round(n),
    feed: {
      connectivity: {
        links: Array.from({ length: 16 }, (_, i) => ({
          index: String(i),
          device_type: 'T'.repeat(40),
          device_subtype: 'S'.repeat(40),
          device_state: 'D'.repeat(40),
          link_state: 'L'.repeat(40),
        })),
      },
    },
  });
  return lines(START, ...Array.from({ length: 2000 }, (_, i) => fat(i + 1)));
}

const PROBLEM_FILES = [
  ['an empty file', () => '', 'That file has no logger lines in it.'],
  [
    'a file with no start line',
    () => lines(round(1)),
    'That file has no start line, so it isn’t a train feed log.',
  ],
  [
    'a file from an unknown version',
    () => lines({ ...START, version: 9 }, round(1)),
    'That log is from a logger version this page doesn’t know.',
  ],
  ['a file with no rounds', () => lines(START), 'That log has no rounds in it.'],
  [
    'a file whose rounds are not rising',
    () => lines(START, round(2), round(1)),
    'That log’s rounds are out of order.',
  ],
  [
    'a file with too many rounds',
    () => lines(START, ...Array.from({ length: 2001 }, (_, i) => round(i + 1))),
    'That log has more than 2,000 rounds. One import takes 2,000 at most.',
  ],
  ['a file too big to send', oversizedLog, 'That log is too big to send.'],
];

describe('Admin: the Train journeys import card', () => {
  it('finds the file input by its visible label: one file of the logger’s kinds', () => {
    installFetch();
    renderApp(PATH);

    const input = screen.getByLabelText('Logger file');
    expect(input).toHaveAttribute('type', 'file');
    expect(input).toHaveAttribute('accept', '.jsonl,.json,.txt');
    expect(input).not.toHaveAttribute('multiple');
    expect(
      screen.getByText(
        'Choose a log the train feed logger wrote. It is read here, in this browser, and only what is listed below is sent.',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Import/ })).not.toBeInTheDocument();
  });

  it('shows what is ready and every term with its value after choosing the example log', async () => {
    const fetchMock = installFetch();
    renderApp(PATH);

    await choose(EXAMPLE);

    expect(await screen.findByText('Ready to import: 6 rounds')).toHaveAttribute('role', 'status');
    expect(screen.getByRole('heading', { name: 'What will be sent' })).toBeInTheDocument();
    expect(screen.getAllByRole('term').map((t) => t.textContent)).toEqual([
      'File name',
      'Rounds',
      'Logged',
      'Train units',
      'Modems',
      'Logger version',
      'Laptop',
      'Class',
      'Left out',
    ]);
    expect(screen.getAllByRole('definition').map((d) => d.textContent)).toEqual([
      'example-log.jsonl',
      '6',
      '21 Sep 2026, 15:13 to 15:16',
      'Unit-001, Unit-002',
      '2',
      '3',
      'Its model, chip and memory',
      'Standard',
      'Nothing',
    ]);
    expect(screen.queryByText(HINT)).not.toBeInTheDocument();
    expect(importButton()).toHaveTextContent('Import');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(imports(fetchMock)).toEqual([]);
  });

  it('says Not sent for a file name the reader leaves out, and lists it under Left out', async () => {
    installFetch();
    renderApp(PATH);

    await choose(EXAMPLE, 'journée-log.jsonl');

    await screen.findByText('Ready to import: 6 rounds');
    const terms = screen.getAllByRole('term').map((t) => t.textContent);
    const values = screen.getAllByRole('definition').map((d) => d.textContent);
    expect(values[terms.indexOf('File name')]).toBe('Not sent');
    expect(values[terms.indexOf('Left out')]).toBe('sourceFile (1)');
    expect(screen.getByText(HINT)).toBeInTheDocument();
  });

  it('shows the left-out sentence for a file with values left out, and not for the next one with none', async () => {
    installFetch();
    renderApp(PATH);
    await choose(EXAMPLE, 'journée-log.jsonl');
    expect(await screen.findByText(HINT)).toBeInTheDocument();

    await choose(EXAMPLE, 'example-log.jsonl');

    // The second file's list is on show before the sentence is looked for: between the two files the card is empty.
    await screen.findByText('example-log.jsonl');
    expect(screen.queryByText(HINT)).not.toBeInTheDocument();
    const terms = screen.getAllByRole('term').map((t) => t.textContent);
    const values = screen.getAllByRole('definition').map((d) => d.textContent);
    expect(values[terms.indexOf('Left out')]).toBe('Nothing');
  });

  it('keeps a good file in the input, so that its name stays on show', async () => {
    installFetch();
    renderApp(PATH);

    await choose(EXAMPLE, 'example-log.jsonl');

    await screen.findByText('Ready to import: 6 rounds');
    expect(field().files).toHaveLength(1);
    expect(field().files[0].name).toBe('example-log.jsonl');
  });

  it('empties the input after a bad log, so that the same file can be chosen again', async () => {
    installFetch();
    renderApp(PATH);

    await choose(lines(START), 'growing-log.jsonl');

    expect(await screen.findByRole('alert')).toHaveTextContent('That log has no rounds in it.');
    expect(field().files).toHaveLength(0);
  });

  it('empties the input after a file that cannot be read', async () => {
    vi.stubGlobal(
      'FileReader',
      class {
        error = new Error('gone');
        readAsText() {
          this.onerror();
        }
      },
    );
    installFetch();
    renderApp(PATH);

    await choose(EXAMPLE);

    await screen.findByRole('alert');
    expect(field().files).toHaveLength(0);
  });

  it('posts what readJourneyLog made of the text, and nothing else', async () => {
    const fetchMock = serving(() => respond(CREATED, 201));
    renderApp(PATH);
    await choose(EXAMPLE, 'example-log.jsonl');

    await userEvent.click(await screen.findByRole('button', { name: 'Import' }));

    await screen.findByText('Imported 1 journey: 6 rounds, 2 viewings linked');
    expect(imports(fetchMock)).toHaveLength(1);
    expect(JSON.parse(imports(fetchMock)[0][1].body)).toEqual(
      readJourneyLog(EXAMPLE, 'example-log.jsonl').request,
    );
  });

  it('on a 201 says what was made, empties the input, removes the summary and focuses the input', async () => {
    serving(() => respond(CREATED, 201));
    renderApp(PATH);
    await choose(EXAMPLE);

    await userEvent.click(await screen.findByRole('button', { name: 'Import' }));

    expect(
      await within(notification()).findByText('Imported 1 journey: 6 rounds, 2 viewings linked'),
    ).toBeInTheDocument();
    expect(field().files).toHaveLength(0);
    expect(screen.queryByRole('heading', { name: 'What will be sent' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Import/ })).not.toBeInTheDocument();
    expect(field()).toHaveFocus();
  });

  it('lets the same file be chosen again after an import', async () => {
    serving(() => respond(CREATED, 201));
    renderApp(PATH);
    await choose(EXAMPLE);
    await userEvent.click(await screen.findByRole('button', { name: 'Import' }));
    await screen.findByText('Imported 1 journey: 6 rounds, 2 viewings linked');

    await choose(EXAMPLE);

    expect(await screen.findByText('Ready to import: 6 rounds')).toBeInTheDocument();
  });

  it('posts once for two presses, and says it is importing with aria-disabled meanwhile', async () => {
    const { gate, release } = held();
    const fetchMock = serving(() => gate);
    renderApp(PATH);
    await choose(EXAMPLE);
    const button = await screen.findByRole('button', { name: 'Import' });

    await userEvent.click(button);
    await userEvent.click(screen.getByRole('button', { name: 'Importing…' }));

    const busy = screen.getByRole('button', { name: 'Importing…' });
    expect(busy).toHaveAttribute('aria-disabled', 'true');
    expect(busy).not.toBeDisabled();
    expect(imports(fetchMock)).toHaveLength(1);

    await act(async () => release(respond(CREATED, 201)));
    await screen.findByText('Imported 1 journey: 6 rounds, 2 viewings linked');
    expect(imports(fetchMock)).toHaveLength(1);
  });

  it('ignores a file chosen while the import is in flight, and empties the input', async () => {
    const { gate, release } = held();
    const fetchMock = serving(() => gate);
    renderApp(PATH);
    await choose(EXAMPLE);
    await userEvent.click(await screen.findByRole('button', { name: 'Import' }));

    await choose(lines(START), 'other-log.jsonl');

    expect(field().files).toHaveLength(0);
    expect(screen.getByRole('button', { name: 'Importing…' })).toBeInTheDocument();
    expect(screen.getByText('Ready to import: 6 rounds')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

    await act(async () => release(respond(CREATED, 201)));
    await screen.findByText('Imported 1 journey: 6 rounds, 2 viewings linked');
    expect(imports(fetchMock)).toHaveLength(1);
  });

  it.each(PROBLEM_FILES)(
    'says %s in one sentence and offers no button',
    async (_name, text, sentence) => {
      installFetch();
      renderApp(PATH);

      await choose(text());

      expect(await screen.findByRole('alert')).toHaveTextContent(sentence);
      expect(screen.queryByRole('button', { name: /^Import/ })).not.toBeInTheDocument();
      expect(screen.queryByRole('heading', { name: 'What will be sent' })).not.toBeInTheDocument();
    },
  );

  it('says a file that cannot be read in one sentence and offers no button', async () => {
    vi.stubGlobal(
      'FileReader',
      class {
        error = new Error('gone');
        readAsText() {
          this.onerror();
        }
      },
    );
    installFetch();
    renderApp(PATH);

    await choose(EXAMPLE);

    expect(await screen.findByRole('alert')).toHaveTextContent('That file couldn’t be read.');
    expect(screen.queryByRole('button', { name: /^Import/ })).not.toBeInTheDocument();
  });

  it('shows the lines not read and what was left out when the log has them', async () => {
    installFetch();
    renderApp(PATH);
    const odd = lines(START, { ...round(1), laptop: { wifi_rate: -4, wifi_rssi: 'x' } }, round(2));

    await choose(`${odd}\nnot json\nalso not json\nstill not json`);

    await screen.findByText('Ready to import: 2 rounds');
    const terms = screen.getAllByRole('term').map((t) => t.textContent);
    const values = screen.getAllByRole('definition').map((d) => d.textContent);
    expect(values[terms.indexOf('Left out')]).toBe('wifiRate (1), wifiRssi (1)');
    expect(values[terms.indexOf('Lines not read')]).toBe('3');
    expect(values[terms.indexOf('Laptop')]).toBe('Not in this log');
  });

  it.each([
    [
      'a 400',
      () => respond({ error: 'Invalid request' }, 400),
      'The server refused this log. Nothing was stored.',
    ],
    [
      'a 409 for a log already imported',
      () => respond({ error: 'Already imported' }, 409),
      'This log is already imported.',
    ],
    [
      'any other 409',
      () => respond({ error: 'An import is running' }, 409),
      'Another import was running. Try again.',
    ],
    ['a 500', () => respond(null, 500), 'Couldn’t import the log. Try again.'],
    [
      'a lost connection',
      () => Promise.reject(new TypeError('offline')),
      'Couldn’t import the log. Try again.',
    ],
  ])(
    'says %s in the alert line and keeps the summary and the button',
    async (_name, answer, sentence) => {
      serving(answer);
      renderApp(PATH);
      await choose(EXAMPLE);

      await userEvent.click(await screen.findByRole('button', { name: 'Import' }));

      expect(await screen.findByRole('alert')).toHaveTextContent(sentence);
      expect(screen.getByText('Ready to import: 6 rounds')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Import' })).not.toHaveAttribute('aria-disabled');
      expect(within(notification()).queryByText(/^Imported/)).not.toBeInTheDocument();
    },
  );

  it('clears a refusal when another file is chosen', async () => {
    serving(() => respond({ error: 'Already imported' }, 409));
    renderApp(PATH);
    await choose(EXAMPLE);
    await userEvent.click(await screen.findByRole('button', { name: 'Import' }));
    await screen.findByRole('alert');

    await choose(lines(START, round(1)), 'second-log.jsonl');

    expect(await screen.findByText('Ready to import: 1 round')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('replaces a refusal with the problem of the next file when that has one', async () => {
    serving(() => respond(null, 500));
    renderApp(PATH);
    await choose(EXAMPLE);
    await userEvent.click(await screen.findByRole('button', { name: 'Import' }));
    await screen.findByText('Couldn’t import the log. Try again.');

    await choose(lines(START), 'empty-log.jsonl');

    expect(await screen.findByRole('alert')).toHaveTextContent('That log has no rounds in it.');
    expect(screen.queryByText('Couldn’t import the log. Try again.')).not.toBeInTheDocument();
  });

  it('clears the file’s problem and the summary when the choice is taken away', async () => {
    installFetch();
    renderApp(PATH);
    await choose(EXAMPLE);
    await screen.findByText('Ready to import: 6 rounds');

    await userEvent.upload(field(), []);

    expect(screen.queryByText('Ready to import: 6 rounds')).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  describe('with a reader that answers when the test says', () => {
    /** FileReaders that wait: `reads` is every file read begun, each with the way to finish it. */
    function slowReaders() {
      const reads = [];
      vi.stubGlobal(
        'FileReader',
        class {
          readAsText(file) {
            reads.push({
              finish: (text) => {
                this.result = text;
                this.onload();
              },
              name: file.name,
            });
          }
        },
      );
      return reads;
    }

    it('shows only the last of two files chosen while the first is still being read', async () => {
      const reads = slowReaders();
      installFetch();
      renderApp(PATH);
      await choose('first', 'first-log.jsonl');
      await choose('second', 'second-log.jsonl');
      expect(reads.map((r) => r.name)).toEqual(['first-log.jsonl', 'second-log.jsonl']);

      await act(async () => reads[1].finish(lines(START, round(1), round(2))));
      expect(await screen.findByText('Ready to import: 2 rounds')).toBeInTheDocument();
      // The first one's read ends late, with a problem: nothing of it shows.
      await act(async () => reads[0].finish(''));

      expect(screen.getByText('Ready to import: 2 rounds')).toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });
  });

  it('brings up the sign-in card on a 401 and keeps the summary and the button', async () => {
    const route = installFetch();
    const base = route.getMockImplementation();
    vi.stubGlobal('fetch', async (url, init = {}) =>
      url === '/api/admin/journeys' ? respond(null, 401) : base(url, init),
    );
    renderApp(PATH);
    await choose(EXAMPLE);

    await userEvent.click(await screen.findByRole('button', { name: 'Import' }));

    expect(await screen.findByRole('dialog', { name: 'Signed out' })).toBeInTheDocument();
    expect(screen.getByText('Ready to import: 6 rounds')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Import' })).toBeInTheDocument();
    expect(screen.queryByText('Couldn’t import the log. Try again.')).not.toBeInTheDocument();
    expect(within(notification()).queryByText(/^Imported/)).not.toBeInTheDocument();
  });

  it('still says what was made when the page was left while the import was in flight', async () => {
    const { gate, release } = held();
    serving(() => gate);
    renderApp(PATH);
    await choose(EXAMPLE);
    await userEvent.click(await screen.findByRole('button', { name: 'Import' }));
    await userEvent.click(screen.getByRole('link', { name: '← Playback' }));
    await screen.findByRole('heading', { level: 1, name: 'Playback' });

    await act(async () => release(respond(CREATED, 201)));

    expect(
      await within(notification()).findByText('Imported 1 journey: 6 rounds, 2 viewings linked'),
    ).toBeInTheDocument();
  });

  it('ends without a sentence or a toast when a refusal comes after the page was left', async () => {
    const { gate, release } = held();
    serving(() => gate);
    renderApp(PATH);
    await choose(EXAMPLE);
    await userEvent.click(await screen.findByRole('button', { name: 'Import' }));
    await userEvent.click(screen.getByRole('link', { name: '← Playback' }));
    await screen.findByRole('heading', { level: 1, name: 'Playback' });

    await act(async () => release(respond(null, 500)));

    expect(within(notification()).queryByText(/^Imported/)).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
