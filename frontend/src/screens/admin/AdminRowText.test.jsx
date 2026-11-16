import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import RowText from './AdminRowText.jsx';

describe('RowText', () => {
  it('shows the title, its year muted after a dot, and the line', () => {
    render(<RowText row={{ title: 'Heat', year: 1995 }} line="OpenSubtitles has 2 files" />);

    expect(screen.getByText('Heat')).toBeInTheDocument();
    expect(screen.getByText('· 1995')).toBeInTheDocument();
    expect(screen.getByText('OpenSubtitles has 2 files')).toBeInTheDocument();
  });

  it('shows no year for a film without one', () => {
    render(<RowText row={{ title: 'Heat', year: null }} line="x" />);

    expect(screen.queryByText(/·/)).not.toBeInTheDocument();
  });

  it('gives the title the id it is asked for, so a button can be described by it', () => {
    render(<RowText row={{ title: 'Heat', year: 1995 }} line="x" titleId="t-1" />);

    expect(screen.getByText('Heat')).toHaveAttribute('id', 't-1');
  });
});
