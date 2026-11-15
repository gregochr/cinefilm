import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { fixtures } from '../../test/mockApi.jsx';
import LibraryChanges from './LibraryChanges.jsx';

/** A report of `n` changes, shaped like the fixture, each with its own summary. */
const reportOf = (n) => ({
  items: Array.from({ length: n }, (_, i) => ({
    ...structuredClone(fixtures.adminGrouping.items[i % 3]),
    summary: `Change ${i + 1}`,
  })),
});

const listed = () =>
  within(screen.getByRole('list', { name: 'Grouping changes' })).getAllByRole('listitem');

describe('LibraryChanges', () => {
  it('is a closed details whose summary counts the changes by kind', () => {
    render(<LibraryChanges report={fixtures.adminGrouping} />);

    expect(screen.getByTestId('library-changes')).not.toHaveAttribute('open');
    expect(screen.getByTestId('library-changes-summary')).toHaveTextContent(
      'What the last scan changed · 1 split, 1 joined, 1 flagged',
    );
  });

  it('opens when the summary is pressed and lists each change with its tag, time and sentence', async () => {
    render(<LibraryChanges report={fixtures.adminGrouping} />);

    await userEvent.click(screen.getByText('What the last scan changed'));

    expect(screen.getByTestId('library-changes')).toHaveAttribute('open');
    const items = listed();
    expect(items).toHaveLength(3);
    expect(items[0]).toHaveTextContent('Joined08 Nov 12:41');
    expect(items[0]).toHaveTextContent('Joined 2 files of "The Two Towers"');
    expect(items[1]).toHaveTextContent('Split07 Nov 09:10');
    expect(items[2]).toHaveTextContent('Check');
  });

  it('shows 24 changes with no button', () => {
    render(<LibraryChanges report={reportOf(24)} />);
    expect(listed()).toHaveLength(24);
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('shows exactly 25 changes with no button', () => {
    render(<LibraryChanges report={reportOf(25)} />);
    expect(listed()).toHaveLength(25);
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('shows 25 of 26 and offers the last one', async () => {
    render(<LibraryChanges report={reportOf(26)} />);
    expect(listed()).toHaveLength(25);
    expect(screen.queryByText('Change 26')).toBeNull();

    await userEvent.click(screen.getByRole('button', { name: 'Show 1 more' }));

    expect(listed()).toHaveLength(26);
    expect(screen.getByText('Change 26')).toBeInTheDocument();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('adds 25 at a time and stops at the end', async () => {
    render(<LibraryChanges report={reportOf(60)} />);
    expect(listed()).toHaveLength(25);

    await userEvent.click(screen.getByRole('button', { name: 'Show 25 more' }));
    expect(listed()).toHaveLength(50);

    await userEvent.click(screen.getByRole('button', { name: 'Show 10 more' }));
    expect(listed()).toHaveLength(60);
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('starts at the first page again when a new report arrives', async () => {
    const { rerender } = render(<LibraryChanges report={reportOf(60)} />);
    await userEvent.click(screen.getByRole('button', { name: 'Show 25 more' }));
    expect(listed()).toHaveLength(50);

    const another = reportOf(60);
    another.items[0].summary = 'A newer scan';
    rerender(<LibraryChanges report={another} />);

    expect(listed()).toHaveLength(25);
    expect(screen.getByRole('button', { name: 'Show 25 more' })).toBeInTheDocument();
  });

  it('keeps the pages already shown when a refetch returns the same lines', async () => {
    const { rerender } = render(<LibraryChanges report={reportOf(60)} />);
    await userEvent.click(screen.getByRole('button', { name: 'Show 25 more' }));

    rerender(<LibraryChanges report={reportOf(60)} />);

    expect(listed()).toHaveLength(50);
  });

  it('keeps the pages already shown when the same report renders again', async () => {
    const report = reportOf(60);
    const { rerender } = render(<LibraryChanges report={report} />);
    await userEvent.click(screen.getByRole('button', { name: 'Show 25 more' }));

    rerender(<LibraryChanges report={report} loading />);

    expect(listed()).toHaveLength(50);
  });

  it('says so when nothing has been grouped, with zero counts in the summary', () => {
    render(<LibraryChanges report={{ items: [] }} />);
    expect(screen.getByText('The last scan changed nothing.')).toBeInTheDocument();
    expect(screen.getByText(/0 split, 0 joined, 0 flagged/)).toBeInTheDocument();
  });

  it('says not scanned yet, not "changed nothing", before any scan has finished', () => {
    render(<LibraryChanges report={{ items: [] }} scanned={false} />);
    expect(screen.getByText('Not scanned yet')).toBeInTheDocument();
    expect(screen.queryByText('The last scan changed nothing.')).toBeNull();
  });

  it('shows a skeleton while the first read runs, with only the title in the summary', () => {
    render(<LibraryChanges report={undefined} loading />);
    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
    expect(screen.getByTestId('library-changes-summary')).toHaveTextContent(
      /^What the last scan changed$/,
    );
  });

  it('shows the error with Try again when the first read failed', async () => {
    const onRetry = vi.fn();
    render(<LibraryChanges report={undefined} error={new Error('x')} onRetry={onRetry} />);

    expect(screen.getByText('Couldn’t load what the last scan changed.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('keeps the list when a later read fails', () => {
    render(<LibraryChanges report={fixtures.adminGrouping} error={new Error('x')} />);
    expect(listed()).toHaveLength(3);
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
