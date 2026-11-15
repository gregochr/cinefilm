import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import PlaybackMinuteStrip from './PlaybackMinuteStrip.jsx';

describe('PlaybackMinuteStrip', () => {
  it('draws the minutes as an image named for the film, with the axis words and the length', () => {
    render(
      <PlaybackMinuteStrip
        film="Rocky"
        minutes={[
          [0, 1000, 1, 60000, 0, 8, 1],
          [1, 900, 4, 56000, 4000, 9, 4],
        ]}
      />,
    );
    expect(
      screen.getByRole('img', { name: 'Speed and quality minute by minute for Rocky' }),
    ).toBeInTheDocument();
    expect(screen.getByText('0:00')).toBeInTheDocument();
    expect(screen.getByText('line = speed · strip = quality')).toBeInTheDocument();
    expect(screen.getByText('0:02')).toBeInTheDocument();
  });

  it('draws a record of five-figure rows the same way', () => {
    render(<PlaybackMinuteStrip film="Rocky" minutes={[[0, 1000, 1, 60000, 0]]} />);
    expect(screen.getByRole('img', { name: /Rocky/ })).toBeInTheDocument();
  });

  it('draws no line when no minute has a speed', () => {
    render(<PlaybackMinuteStrip film="Rocky" minutes={[[0, 0, 1, 60000, 0]]} />);
    expect(screen.getByRole('img', { name: /Rocky/ })).toBeInTheDocument();
  });

  it('says so when there is no readable minute', () => {
    render(<PlaybackMinuteStrip film="Rocky" minutes={[]} />);
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
    expect(screen.getByText('No minute by minute figures for this session.')).toBeInTheDocument();
  });
});
