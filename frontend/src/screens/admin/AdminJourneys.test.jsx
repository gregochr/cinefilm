import { act, screen } from '@testing-library/react';
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

  it('goes back to Playback from the link', async () => {
    installFetch();
    renderApp('/admin/playback/journeys');

    await userEvent.click(screen.getByRole('link', { name: '← Playback' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Playback' })).toBeInTheDocument();
  });
});
