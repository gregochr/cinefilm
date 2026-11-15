import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import fixture from '../../test/fixtures/admin-summary.json';
import AdminStatusStrip from './AdminStatusStrip.jsx';

const NOW = Date.parse('2026-11-08T12:53:10Z'); // 12 min after the fixture's scan

function renderStrip(props) {
  return render(
    <MemoryRouter>
      <AdminStatusStrip now={NOW} onRetry={() => {}} {...props} />
    </MemoryRouter>,
  );
}

describe('AdminStatusStrip', () => {
  it('draws the three cards from the summary, each with its link', () => {
    renderStrip({ summary: fixture, error: null });

    const cards = within(screen.getByRole('list', { name: 'Status' })).getAllByRole('listitem');
    expect(cards).toHaveLength(3);
    expect(cards[0]).toHaveTextContent('Library538 films · scanned 12 min agoLibrary →');
    expect(cards[1]).toHaveTextContent(
      'PlaybackLast 30 days: 2 of 3 film sessions played smoothlyPlayback →',
    );
    expect(cards[2]).toHaveTextContent('Playing nowHeat · Mac · 720pSee stream →');
    expect(screen.getByRole('link', { name: 'Library →' })).toHaveAttribute(
      'href',
      '/admin/library',
    );
    expect(screen.getByRole('link', { name: 'Playback →' })).toHaveAttribute(
      'href',
      '/admin/playback',
    );
    expect(screen.getByRole('link', { name: 'See stream →' })).toHaveAttribute(
      'href',
      '/admin/playback',
    );
  });

  it('says nothing is playing, and that no films were watched', () => {
    renderStrip({
      summary: { ...fixture, playingNow: [], playback: { films: 0, smooth: 0 } },
      error: null,
    });

    expect(screen.getByText('Nothing playing')).toBeInTheDocument();
    expect(screen.getByText('No films watched in the last 30 days')).toBeInTheDocument();
  });

  it('adds "+1 more" when a second stream is playing', () => {
    const second = { ...fixture.playingNow[0], playbackId: 'x', filmTitle: 'Alien' };
    renderStrip({
      summary: { ...fixture, playingNow: [fixture.playingNow[0], second] },
      error: null,
    });

    expect(screen.getByText('Heat · Mac · 720p · +1 more')).toBeInTheDocument();
  });

  it('says when no scan has finished', () => {
    renderStrip({
      summary: { ...fixture, library: { ...fixture.library, lastScanFinishedAt: null } },
      error: null,
    });

    expect(screen.getByText('538 films · not scanned yet')).toBeInTheDocument();
  });

  it('says the last scan failed, in the error colour rather than amber', () => {
    renderStrip({
      summary: { ...fixture, library: { ...fixture.library, lastScanError: 'boom' } },
      error: null,
    });

    const text = screen.getByText('538 films · last scan failed 12 min ago');
    expect(text).toHaveClass('admin__strip-text--error');
  });

  it('does not colour a successful scan as an error', () => {
    renderStrip({ summary: fixture, error: null });

    expect(screen.getByText('538 films · scanned 12 min ago')).not.toHaveClass(
      'admin__strip-text--error',
    );
  });

  it('keeps the Playback and Playing now cards off the phone and the Library card on it', () => {
    renderStrip({ summary: fixture, error: null });

    // The phone layout is a CSS rule on this class (jsdom has no media queries, and the cards have no other handle).
    const [library, playback, playing] = screen.getAllByRole('listitem');
    expect(library).not.toHaveClass('admin__strip-card--wide-only');
    expect(playback).toHaveClass('admin__strip-card--wide-only');
    expect(playing).toHaveClass('admin__strip-card--wide-only');
  });

  it('holds the card shapes with a static skeleton until the first summary arrives', () => {
    renderStrip({ summary: null, error: null });

    const status = screen.getByRole('status', { name: 'Loading the status' });
    expect(within(status).getAllByTestId('skeleton')).toHaveLength(3);
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('shows an error banner with a retry that calls onRetry', async () => {
    const onRetry = vi.fn();
    renderStrip({ summary: null, error: new Error('boom'), onRetry });

    const banner = screen.getByRole('alert');
    expect(banner).toHaveTextContent('Couldn’t load the status.');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    await userEvent.click(within(banner).getByRole('button', { name: 'Try again' }));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});
