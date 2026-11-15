import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { fixtures } from '../test/mockApi.jsx';
import PlaybackMinuteStrip from './PlaybackMinuteStrip.jsx';

/** The minutes of the API-shaped record fixture: seven figures a row, 480p and 360p, one stalled minute. */
const sevenFigures = () => structuredClone(fixtures.adminPlaybackRecord.report.minutes);

describe('PlaybackMinuteStrip', () => {
  it('draws the minutes as an image named for the film, with the axis words and the length', () => {
    render(<PlaybackMinuteStrip film="Rocky" minutes={sevenFigures()} />);
    expect(
      screen.getByRole('img', { name: 'Speed and quality minute by minute for Rocky' }),
    ).toBeInTheDocument();
    expect(screen.getByText('0:00')).toBeInTheDocument();
    expect(screen.getByText('line = speed · strip = quality')).toBeInTheDocument();
    expect(screen.getByText('0:06')).toBeInTheDocument();
  });

  it('draws a band per minute in the colour of its rung, a line, and a dot where a minute stalled', () => {
    render(<PlaybackMinuteStrip film="Rocky" minutes={sevenFigures()} />);
    const bands = screen.getAllByTestId('strip-band');
    expect(bands).toHaveLength(6);
    // 480p and below are the darkest bucket.
    expect(bands.every((band) => band.getAttribute('fill') === '#5E574C')).toBe(true);
    expect(screen.getByTestId('strip-line').getAttribute('d')).toMatch(/^M/);
    expect(screen.getAllByTestId('strip-stall')).toHaveLength(1);
  });

  it('draws rows of five figures to the same bands, line and dots as rows of seven', () => {
    const five = sevenFigures().map((row) => row.slice(0, 5));
    render(<PlaybackMinuteStrip film="Rocky" minutes={five} />);
    expect(screen.getAllByTestId('strip-band')).toHaveLength(6);
    expect(screen.getByTestId('strip-line')).toBeInTheDocument();
    expect(screen.getAllByTestId('strip-stall')).toHaveLength(1);
  });

  it('colours each band by the rung played', () => {
    const rows = sevenFigures().slice(0, 3);
    rows[0][2] = 1;
    rows[1][2] = 2;
    rows[2][2] = 3;
    render(<PlaybackMinuteStrip film="Rocky" minutes={rows} />);
    expect(screen.getAllByTestId('strip-band').map((b) => b.getAttribute('fill'))).toEqual([
      '#EDE6DA',
      '#BFB5A5',
      '#8E8576',
    ]);
  });

  it('draws no line when no minute has a speed', () => {
    const rows = sevenFigures().map((row) => [row[0], 0, ...row.slice(2)]);
    render(<PlaybackMinuteStrip film="Rocky" minutes={rows} />);
    expect(screen.getByRole('img', { name: /Rocky/ })).toBeInTheDocument();
    expect(screen.queryByTestId('strip-line')).not.toBeInTheDocument();
    expect(screen.getAllByTestId('strip-band')).toHaveLength(6);
  });

  it('says so when there is no readable minute', () => {
    render(<PlaybackMinuteStrip film="Rocky" minutes={[]} />);
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
    expect(screen.getByText('No minute by minute figures for this session.')).toBeInTheDocument();
  });
});
