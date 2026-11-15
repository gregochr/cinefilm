import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import SubtitleSearchLine from './SubtitleSearchLine.jsx';

const scan = (over = {}) => ({
  openSubtitlesEnabled: true,
  openSubtitlesRunning: false,
  openSubtitlesLastSearchedAt: '2026-11-12T06:00:00Z',
  ...over,
});

describe('SubtitleSearchLine', () => {
  it('says when subtitles were last searched, as a status', () => {
    render(<SubtitleSearchLine scan={scan()} />);

    expect(screen.getByRole('status')).toHaveTextContent('Subtitles searched 12 Nov 06:00');
  });

  it('says searching while a search runs, and not yet before the first', () => {
    const { rerender } = render(<SubtitleSearchLine scan={scan({ openSubtitlesRunning: true })} />);
    expect(screen.getByRole('status')).toHaveTextContent('Searching for subtitles…');

    rerender(<SubtitleSearchLine scan={scan({ openSubtitlesLastSearchedAt: null })} />);
    expect(screen.getByRole('status')).toHaveTextContent('Subtitles not searched yet');
  });

  it('draws nothing when the search is off or the scan has not been read', () => {
    const { rerender } = render(
      <SubtitleSearchLine scan={scan({ openSubtitlesEnabled: false })} />,
    );
    expect(screen.queryByRole('status')).not.toBeInTheDocument();

    rerender(<SubtitleSearchLine scan={null} />);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});
