import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import {
  DetailSkeleton,
  GridSkeleton,
  HomeSkeleton,
  SkeletonBlock,
  SkeletonRow,
} from './Skeleton.jsx';

// Skeletons are static placeholders: announced once as "Loading", their blocks hidden from AT.
describe('skeletons', () => {
  it.each([
    ['home', <HomeSkeleton key="h" />],
    ['grid', <GridSkeleton key="g" />],
    ['detail', <DetailSkeleton key="d" />],
  ])('the %s skeleton is a single Loading status', (_name, ui) => {
    render(ui);
    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
  });

  it('a block is hidden from assistive technology and keeps its class and size', () => {
    render(<SkeletonBlock className="skeleton--hero" style={{ height: 96 }} />);
    const block = screen.getByTestId('skeleton');
    expect(block).toHaveAttribute('aria-hidden', 'true');
    expect(block).toHaveClass('skeleton', 'skeleton--hero');
    expect(block).toHaveStyle({ height: '96px' });
  });

  it('a row is entirely hidden from assistive technology', () => {
    render(<SkeletonRow count={3} />);
    expect(screen.getByTestId('skeleton-row')).toHaveAttribute('aria-hidden', 'true');
    expect(screen.getAllByTestId('skeleton')).toHaveLength(3);
  });

  it('a still-variant row uses 16:9 blocks and the default uses posters', () => {
    const { rerender } = render(<SkeletonRow count={2} variant="stills" />);
    expect(screen.getByTestId('skeleton-row')).toHaveClass('row__grid--stills');
    expect(screen.getAllByTestId('skeleton')[0]).toHaveClass('skeleton--still');
    rerender(<SkeletonRow count={2} />);
    expect(screen.getByTestId('skeleton-row')).toHaveClass('row__grid--posters');
    expect(screen.getAllByTestId('skeleton')[0]).toHaveClass('skeleton--poster');
  });

  it('the grid skeleton defaults to eighteen tiles and honours a count', () => {
    const { rerender } = render(<GridSkeleton />);
    expect(screen.getAllByTestId('skeleton')).toHaveLength(18);
    rerender(<GridSkeleton count={4} />);
    expect(screen.getAllByTestId('skeleton')).toHaveLength(4);
  });
});
