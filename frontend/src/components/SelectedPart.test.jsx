import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fixtures } from '../test/mockApi.jsx';
import SelectedPart from './SelectedPart.jsx';

const mockRecord = vi.hoisted(() => vi.fn());
vi.mock('../state/usePlaybackRecord.js', () => ({ usePlaybackRecord: mockRecord }));

const part = {
  playbackId: 'p-1',
  startedAt: '2026-11-12T17:56:53.956Z',
  endedAt: '2026-11-12T20:33:53.080Z',
  watchedMs: 6_360_000,
  environment: 'train',
};
const full = () => {
  const record = structuredClone(fixtures.adminPlaybackRecord);
  Object.assign(record.report, {
    startupMs: 6300,
    stallCount: 155,
    stallMs: 2_011_000,
    watchedMs: 6_360_000,
    reconnectCount: 0,
    fellBack: false,
  });
  return record;
};
const state = (over) => ({ record: null, error: null, loading: false, reload: vi.fn(), ...over });

beforeEach(() => mockRecord.mockReset());

describe('SelectedPart', () => {
  it('reads the record of its own part', () => {
    mockRecord.mockReturnValue(state({ loading: true }));
    render(<SelectedPart part={part} film="The Rock" />);
    expect(mockRecord).toHaveBeenCalledWith('p-1');
  });

  it('shows a skeleton while loading', () => {
    mockRecord.mockReturnValue(state({ loading: true }));
    render(<SelectedPart part={part} film="The Rock" />);
    expect(
      screen.getByRole('status', { name: 'Loading the minute by minute view' }),
    ).toBeInTheDocument();
  });

  it('shows the strip, and the part’s times at the front of its figures line', () => {
    mockRecord.mockReturnValue(state({ record: full() }));
    render(<SelectedPart part={part} film="The Rock" />);
    expect(screen.getByRole('img', { name: /The Rock/ })).toBeInTheDocument();
    expect(screen.getByText('17:56–20:33 · Start 6.3 s · 155 stalls, 33m 31s')).toBeInTheDocument();
  });

  it('hands the record to its listener', () => {
    const record = full();
    const onRecord = vi.fn();
    mockRecord.mockReturnValue(state({ record }));
    render(<SelectedPart part={part} film="The Rock" onRecord={onRecord} />);
    expect(onRecord).toHaveBeenCalledWith(record);
  });

  it('says there are no minutes for a record that is gone (404), with no retry', () => {
    mockRecord.mockReturnValue(state({ error: { status: 404 } }));
    render(<SelectedPart part={part} film="The Rock" />);
    expect(screen.getByText('No minute by minute figures for this session.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
  });

  it('shows an error with a retry that reloads this part', async () => {
    const reload = vi.fn();
    mockRecord.mockReturnValue(state({ error: { status: 500 }, reload }));
    render(<SelectedPart part={part} film="The Rock" />);
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Couldn’t load that session’s minutes.');
    await userEvent.setup().click(within(alert).getByRole('button', { name: 'Try again' }));
    expect(reload).toHaveBeenCalledTimes(1);
  });
});
