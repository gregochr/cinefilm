import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, renderApp, respond } from '../../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('Admin: the Train journeys page', () => {
  it('has a back link to Playback, the title and the import card', () => {
    installFetch();
    renderApp('/admin/playback/journeys');

    expect(screen.getByRole('link', { name: '← Playback' })).toHaveAttribute(
      'href',
      '/admin/playback',
    );
    expect(screen.getByRole('heading', { level: 1, name: 'Train journeys' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Import a journey' })).toBeInTheDocument();
  });

  it('puts the list card under the import card', async () => {
    installFetch();
    renderApp('/admin/playback/journeys');

    await screen.findByText('No journeys yet. Import a log above.');
    const cards = screen.getAllByRole('region').map((card) => card.getAttribute('aria-labelledby'));
    expect(cards).toEqual(['admin-journey-import-title', 'admin-journey-list-title']);
  });

  describe('after an import', () => {
    const OLD = {
      id: 1,
      runStartedMs: 1898891940000,
      segment: 0,
      trainUnit: 'Unit-001',
      travelClass: 'standard',
      osVersion: null,
      loggerVersion: 3,
      sourceFile: null,
      importedAt: '2030-03-05T09:00:00Z',
      device: null,
      samples: 363,
      from: '2030-03-04T22:00:00Z',
      to: '2030-03-05T00:47:30Z',
      linked: 2,
      train: null,
    };
    const NEW = {
      ...OLD,
      id: 2,
      runStartedMs: 1899000000000,
      trainUnit: 'Unit-002',
      samples: 6,
      linked: 0,
    };
    const LOG = [
      JSON.stringify({ event: 'start', tool: 'train-feed-logger', version: 3, t: 1790000000000 }),
      JSON.stringify({ t: 1790000030000, round: 1 }),
    ].join('\n');
    const reads = (fetchMock) =>
      fetchMock.mock.calls.filter(
        ([url, init]) => url === '/api/admin/journeys' && init?.method === 'GET',
      );
    const importLog = async () => {
      await userEvent.upload(screen.getByLabelText('Logger file'), new File([LOG], 'a.jsonl'));
      await userEvent.click(await screen.findByRole('button', { name: 'Import' }));
    };

    it('asks for the list again after a 201, keeping the old rows while the second read is open', async () => {
      let release;
      const gate = new Promise((resolve) => {
        release = resolve;
      });
      let readCount = 0;
      const fetchMock = installFetch((method, url) => {
        if (url !== '/api/admin/journeys') return null;
        if (method === 'POST') return respond({ journeys: [{ samples: 6, linked: 0 }] }, 201);
        readCount += 1;
        return readCount === 1 ? respond({ journeys: [OLD] }) : gate;
      });
      renderApp('/admin/playback/journeys');
      await screen.findByText('Unit-001 · Standard · 363 rounds · 2 viewings');

      await importLog();

      await screen.findByText('Imported 1 journey: 6 rounds, no viewings linked');
      expect(reads(fetchMock)).toHaveLength(2);
      expect(screen.getByText('Unit-001 · Standard · 363 rounds · 2 viewings')).toBeInTheDocument();
      expect(screen.queryByRole('status', { name: 'Loading journeys' })).not.toBeInTheDocument();

      await act(async () => release(respond({ journeys: [NEW, OLD] })));

      expect(
        await screen.findByText('Unit-002 · Standard · 6 rounds · no viewings'),
      ).toBeInTheDocument();
      expect(screen.getAllByRole('listitem')).toHaveLength(2);
    });

    it('shows the failure of a read again in place of the rows, and its retry keeps focus until the rows show', async () => {
      let release;
      const gate = new Promise((resolve) => {
        release = resolve;
      });
      let readCount = 0;
      installFetch((method, url) => {
        if (url !== '/api/admin/journeys') return null;
        if (method === 'POST') return respond({ journeys: [{ samples: 6, linked: 0 }] }, 201);
        readCount += 1;
        if (readCount === 2) return respond({ error: 'x' }, 500);
        return readCount === 1 ? respond({ journeys: [OLD] }) : gate;
      });
      renderApp('/admin/playback/journeys');
      await screen.findByRole('list', { name: 'Stored journeys' });

      await importLog();

      expect(await screen.findByText('Couldn’t load the journeys.')).toBeInTheDocument();
      expect(screen.queryByRole('list', { name: 'Stored journeys' })).not.toBeInTheDocument();
      await userEvent.click(screen.getByRole('button', { name: 'Check again' }));
      expect(screen.getByRole('button', { name: 'Check again' })).toHaveAttribute(
        'aria-disabled',
        'true',
      );
      expect(screen.getByRole('button', { name: 'Check again' })).toHaveFocus();
      expect(screen.queryByRole('list', { name: 'Stored journeys' })).not.toBeInTheDocument();

      await act(async () => release(respond({ journeys: [NEW, OLD] })));

      expect(
        await screen.findByText('Unit-002 · Standard · 6 rounds · no viewings'),
      ).toBeInTheDocument();
      expect(screen.getByRole('heading', { level: 2, name: 'Stored journeys' })).toHaveFocus();
    });

    it('leaves focus on the file input when a retry is overtaken by an import and the list arrives', async () => {
      let readCount = 0;
      installFetch((method, url) => {
        if (url !== '/api/admin/journeys') return null;
        if (method === 'POST') return respond({ journeys: [{ samples: 6, linked: 0 }] }, 201);
        readCount += 1;
        if (readCount === 1) return respond({ error: 'x' }, 500);
        // The retry's read hangs; the import's read again overtakes it.
        return readCount === 2 ? new Promise(() => {}) : respond({ journeys: [NEW, OLD] });
      });
      renderApp('/admin/playback/journeys');
      await userEvent.click(await screen.findByRole('button', { name: 'Check again' }));

      await importLog();

      expect(
        await screen.findByText('Unit-002 · Standard · 6 rounds · no viewings'),
      ).toBeInTheDocument();
      expect(screen.getByLabelText('Logger file')).toHaveFocus();
    });

    it('asks for nothing after a failed import', async () => {
      const fetchMock = installFetch((method, url) =>
        method === 'POST' && url === '/api/admin/journeys' ? respond({ error: 'x' }, 400) : null,
      );
      renderApp('/admin/playback/journeys');
      await screen.findByText('No journeys yet. Import a log above.');

      await importLog();

      expect(
        await screen.findByText('The server refused this log. Nothing was stored.'),
      ).toBeInTheDocument();
      expect(reads(fetchMock)).toHaveLength(1);
    });
  });

  describe('deleting a journey', () => {
    const at = (hour) => ({
      id: hour,
      runStartedMs: 1898891940000 + hour,
      segment: 0,
      trainUnit: `Unit-0${hour}`,
      travelClass: 'standard',
      osVersion: null,
      loggerVersion: 3,
      sourceFile: null,
      importedAt: '2030-03-05T09:00:00Z',
      device: null,
      samples: hour,
      from: `2030-03-04T${hour}:00:00Z`,
      to: `2030-03-04T${hour}:30:00Z`,
      linked: 0,
      train: null,
    });
    const [A, B, C] = [at(10), at(11), at(12)];
    const nameOf = (hour) => `Delete the journey logged 4 Mar 2030, ${hour}:00 to ${hour}:30`;
    const rowButton = (hour) => screen.getByRole('button', { name: nameOf(hour) });
    const press = (hour) => userEvent.click(rowButton(hour));
    const confirm = () => userEvent.click(screen.getByRole('button', { name: 'Delete journey' }));
    const reads = (fetchMock) =>
      fetchMock.mock.calls.filter(
        ([url, init]) => url === '/api/admin/journeys' && init?.method === 'GET',
      );
    const deletes = (fetchMock) =>
      fetchMock.mock.calls.filter(([, init]) => init?.method === 'DELETE');
    const held = () => {
      let release;
      const gate = new Promise((resolve) => {
        release = resolve;
      });
      return { gate, release };
    };
    /**
     * The server: it holds `journeys`; a delete answers with `answer(id)` (default: a 204 that takes the journey away)
     * and a read with `read(count)` (default: what it holds).
     */
    const serve = (journeys, { answer, read, imported } = {}) => {
      const server = { journeys, reads: 0 };
      const fetchMock = installFetch((method, url) => {
        if (method === 'POST' && url === '/api/admin/journeys') {
          server.journeys = imported;
          return respond({ journeys: [{ samples: 6, linked: 0 }] }, 201);
        }
        if (method === 'DELETE' && url.startsWith('/api/admin/journeys/')) {
          const id = Number(url.split('/').pop());
          if (answer) return answer(id, server);
          server.journeys = server.journeys.filter((j) => j.id !== id);
          return respond(null, 204);
        }
        if (method === 'GET' && url === '/api/admin/journeys') {
          server.reads += 1;
          return read?.(server.reads, server) ?? respond({ journeys: server.journeys });
        }
        return null;
      });
      return { fetchMock, server };
    };
    const open = async () => {
      renderApp('/admin/playback/journeys');
      await screen.findByRole('list', { name: 'Stored journeys' });
    };
    const LOG = [
      JSON.stringify({ event: 'start', tool: 'train-feed-logger', version: 3, t: 1790000000000 }),
      JSON.stringify({ t: 1790000030000, round: 1 }),
    ].join('\n');

    it('sends the delete to the journey’s own id', async () => {
      const { fetchMock } = serve([A, B, C]);
      await open();

      await press(11);
      await confirm();

      await screen.findByText('Journey deleted');
      expect(deletes(fetchMock)).toHaveLength(1);
      expect(deletes(fetchMock)[0][0]).toBe('/api/admin/journeys/11');
    });

    it('says so, reads the list again and drops the row, on a 204', async () => {
      let release;
      const { fetchMock } = serve([A, B, C], {
        read: (count) => {
          if (count !== 2) return null;
          const { gate, release: open } = held();
          release = open;
          return gate;
        },
      });
      await open();

      await press(11);
      await confirm();

      expect(await screen.findByText('Journey deleted')).toBeInTheDocument();
      await waitFor(() => expect(reads(fetchMock)).toHaveLength(2));
      expect(screen.queryByRole('group')).not.toBeInTheDocument();
      expect(screen.getAllByRole('listitem')).toHaveLength(3);
      await act(async () => release(respond({ journeys: [A, C] })));

      await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(2));
      expect(screen.queryByRole('button', { name: nameOf(11) })).not.toBeInTheDocument();
    });

    it('puts focus on the next row’s Delete… when a row in the middle is gone', async () => {
      serve([A, B, C]);
      await open();

      await press(11);
      await confirm();

      await waitFor(() => expect(rowButton(12)).toHaveFocus());
    });

    it('puts focus on the previous row’s Delete… when the last row is gone', async () => {
      serve([A, B, C]);
      await open();

      await press(12);
      await confirm();

      await waitFor(() => expect(rowButton(11)).toHaveFocus());
    });

    it('puts focus on the page’s title when the only journey is gone', async () => {
      serve([A]);
      await open();

      await press(10);
      await confirm();

      expect(await screen.findByText('No journeys yet. Import a log above.')).toBeInTheDocument();
      await waitFor(() =>
        expect(screen.getByRole('heading', { level: 1, name: 'Train journeys' })).toHaveFocus(),
      );
    });

    it('tells a 404 as already deleted, reads the list again and moves focus the same way', async () => {
      const { fetchMock } = serve([A, B, C], {
        answer: (id, server) => {
          server.journeys = server.journeys.filter((j) => j.id !== id);
          return respond({ error: 'No such journey' }, 404);
        },
      });
      await open();

      await press(11);
      await confirm();

      expect(await screen.findByText('That journey was already deleted')).toBeInTheDocument();
      await waitFor(() => expect(rowButton(12)).toHaveFocus());
      expect(reads(fetchMock)).toHaveLength(2);
      expect(screen.queryByRole('button', { name: nameOf(11) })).not.toBeInTheDocument();
    });

    it('leaves focus where the owner moved it while the list was read again', async () => {
      let release;
      serve([A, B, C], {
        read: (count) => {
          if (count !== 2) return null;
          const { gate, release: open } = held();
          release = open;
          return gate;
        },
      });
      await open();
      await press(11);
      await confirm();
      await screen.findByText('Journey deleted');
      await userEvent.click(screen.getByLabelText('Logger file'));
      expect(screen.getByLabelText('Logger file')).toHaveFocus();

      await act(async () => release(respond({ journeys: [A, C] })));

      await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(2));
      expect(screen.getByLabelText('Logger file')).toHaveFocus();
    });

    it('leaves focus on the file input when the owner moved there while the delete ran', async () => {
      const removal = held();
      serve([A, B, C], { answer: () => removal.gate });
      await open();
      await press(11);
      await confirm();
      await userEvent.click(screen.getByLabelText('Logger file'));

      await act(async () => removal.release(respond(null, 204)));

      await screen.findByText('Journey deleted');
      expect(screen.getByLabelText('Logger file')).toHaveFocus();
    });

    it('puts focus on the list card’s heading when the list read again fails', async () => {
      serve([A, B, C], { read: (count) => (count === 2 ? respond({ error: 'x' }, 500) : null) });
      await open();

      await press(11);
      await confirm();

      expect(await screen.findByText('Couldn’t load the journeys.')).toBeInTheDocument();
      await waitFor(() =>
        expect(screen.getByRole('heading', { level: 2, name: 'Stored journeys' })).toHaveFocus(),
      );
      expect(screen.queryByRole('list', { name: 'Stored journeys' })).not.toBeInTheDocument();
    });

    it('leaves focus where the owner put it when the list read again fails', async () => {
      const read = held();
      serve([A, B, C], { read: (count) => (count === 2 ? read.gate : null) });
      await open();
      await press(11);
      await confirm();
      await screen.findByText('Journey deleted');
      await userEvent.click(screen.getByLabelText('Logger file'));

      await act(async () => read.release(respond({ error: 'x' }, 500)));

      expect(await screen.findByText('Couldn’t load the journeys.')).toBeInTheDocument();
      expect(screen.getByLabelText('Logger file')).toHaveFocus();
    });

    it('keeps focus on the row’s Delete… while the list is read again, then moves it to the neighbour', async () => {
      const read = held();
      serve([A, B, C], { read: (count) => (count === 2 ? read.gate : null) });
      await open();

      await press(11);
      await confirm();

      await screen.findByText('Journey deleted');
      expect(rowButton(11)).toHaveFocus();
      await act(async () => read.release(respond({ journeys: [A, C] })));
      await waitFor(() => expect(rowButton(12)).toHaveFocus());
    });

    describe('when an import is read while the delete runs', () => {
      /** One journey stored; an import's list read is held; the delete is confirmed; then the read is let go. */
      const importThenDelete = async (afterDelete) => {
        const read = held();
        const removal = held();
        serve([A], {
          imported: [B, A],
          read: (count) => (count === 2 ? read.gate : null),
          answer: async (id, server) => {
            await removal.gate;
            server.journeys = afterDelete;
            return respond(null, 204);
          },
        });
        await open();
        await userEvent.upload(screen.getByLabelText('Logger file'), new File([LOG], 'a.jsonl'));
        await userEvent.click(await screen.findByRole('button', { name: 'Import' }));
        await screen.findByText('Imported 1 journey: 6 rounds, no viewings linked');
        await press(10);
        await confirm();
        await act(async () => read.release(respond({ journeys: [B, A] })));
        await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(2));
        await act(async () => removal.release());
      };

      it('puts focus on the remaining row’s Delete… when the latest list gives it as the neighbour', async () => {
        await importThenDelete([B]);

        await waitFor(() => expect(rowButton(11)).toHaveFocus());
        expect(screen.queryByRole('button', { name: nameOf(10) })).not.toBeInTheDocument();
      });

      it('puts focus on the page’s title when no row is left', async () => {
        await importThenDelete([]);

        expect(await screen.findByText('No journeys yet. Import a log above.')).toBeInTheDocument();
        await waitFor(() =>
          expect(screen.getByRole('heading', { level: 1, name: 'Train journeys' })).toHaveFocus(),
        );
      });
    });

    it('closes the first confirmation when another row’s is opened', async () => {
      serve([A, B, C]);
      await open();
      await press(10);
      expect(screen.getAllByRole('group')).toHaveLength(1);

      await userEvent.click(rowButton(11));

      const groups = screen.getAllByRole('group');
      expect(groups).toHaveLength(1);
      expect(within(screen.getAllByRole('listitem')[1]).getByRole('group')).toBe(groups[0]);
      expect(rowButton(10)).toBeInTheDocument();
      expect(rowButton(10)).not.toHaveFocus();
      expect(screen.getByRole('button', { name: 'Keep' })).toHaveFocus();
    });

    it('puts focus on Keep of the row opened above the one that was open', async () => {
      serve([A, B, C]);
      await open();
      await press(11);

      await press(10);

      const groups = screen.getAllByRole('group');
      expect(groups).toHaveLength(1);
      expect(within(screen.getAllByRole('listitem')[0]).getByRole('group')).toBe(groups[0]);
      expect(screen.getByRole('button', { name: 'Keep' })).toHaveFocus();
      expect(rowButton(11)).not.toHaveFocus();
    });

    it('forgets a refusal shown in a confirmation when another is opened', async () => {
      serve([A, B, C], { answer: () => respond({ error: 'x' }, 500) });
      await open();
      await press(10);
      await confirm();
      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Couldn’t delete the journey. Try again.',
      );

      await userEvent.click(rowButton(11));
      await userEvent.click(rowButton(10));

      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('forgets a refusal when the confirmation is closed with Keep', async () => {
      serve([A, B, C], { answer: () => respond({ error: 'x' }, 500) });
      await open();
      await press(10);
      await confirm();
      await screen.findByRole('alert');

      await userEvent.click(screen.getByRole('button', { name: 'Keep' }));
      await press(10);

      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('keeps a confirmation open when an import reads the list again, and shows the new row', async () => {
      serve([A, B], { imported: [C, A, B] });
      await open();
      await press(11);
      await userEvent.upload(screen.getByLabelText('Logger file'), new File([LOG], 'a.jsonl'));

      await userEvent.click(await screen.findByRole('button', { name: 'Import' }));

      await screen.findByText('Imported 1 journey: 6 rounds, no viewings linked');
      await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(3));
      expect(
        within(screen.getAllByRole('listitem')[2]).getByRole('group', {
          name: 'Delete this journey?',
        }),
      ).toBeInTheDocument();
    });

    it('shows a 409 in the confirmation, which stays open with both buttons live', async () => {
      serve([A, B], { answer: () => respond({ error: 'Try again' }, 409) });
      await open();

      await press(10);
      await confirm();

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Another request was running. Try again.',
      );
      expect(screen.getByRole('group', { name: 'Delete this journey?' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Delete journey' })).not.toHaveAttribute(
        'aria-disabled',
      );
      expect(screen.getByRole('button', { name: 'Keep' })).not.toHaveAttribute('aria-disabled');
    });

    it('shows a 500 in the confirmation and asks for no list', async () => {
      const { fetchMock } = serve([A, B], { answer: () => respond({ error: 'x' }, 500) });
      await open();

      await press(10);
      await confirm();

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Couldn’t delete the journey. Try again.',
      );
      expect(screen.getByRole('group', { name: 'Delete this journey?' })).toBeInTheDocument();
      expect(reads(fetchMock)).toHaveLength(1);
    });

    it('shows a lost connection in the confirmation', async () => {
      serve([A, B], { answer: () => Promise.reject(new TypeError('offline')) });
      await open();

      await press(10);
      await confirm();

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Couldn’t delete the journey. Try again.',
      );
    });

    it('keeps the confirmation open with no sentence of its own on a 401', async () => {
      serve([A, B], { answer: () => respond({ error: 'Sign in' }, 401) });
      await open();

      await press(10);
      await confirm();

      await waitFor(() =>
        expect(screen.getByRole('button', { name: 'Delete journey' })).not.toHaveAttribute(
          'aria-disabled',
        ),
      );
      expect(screen.getByRole('group', { name: 'Delete this journey?' })).toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('sends one request for two presses in the same moment', async () => {
      const { gate, release } = held();
      const { fetchMock } = serve([A, B], { answer: () => gate });
      await open();
      await press(10);

      const button = screen.getByRole('button', { name: 'Delete journey' });
      act(() => {
        button.click();
        button.click();
      });
      await act(async () => release(respond(null, 204)));

      expect(deletes(fetchMock)).toHaveLength(1);
    });

    it('does nothing for another row’s Delete… while a delete runs', async () => {
      const { gate, release } = held();
      serve([A, B], { answer: () => gate });
      await open();
      await press(10);
      await confirm();

      await userEvent.click(rowButton(11));

      expect(screen.getAllByRole('group')).toHaveLength(1);
      expect(screen.getByRole('button', { name: 'Deleting…' })).toBeInTheDocument();
      await act(async () => release(respond({ error: 'x' }, 500)));
      await screen.findByRole('alert');
    });
  });

  it('goes back to Playback from the link', async () => {
    installFetch();
    renderApp('/admin/playback/journeys');

    await userEvent.click(screen.getByRole('link', { name: '← Playback' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Playback' })).toBeInTheDocument();
  });
});
