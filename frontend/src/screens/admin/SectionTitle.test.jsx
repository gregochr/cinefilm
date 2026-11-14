import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import SectionTitle from './SectionTitle.jsx';

describe('SectionTitle', () => {
  it('renders its text as a level 1 heading that script can focus but Tab skips', () => {
    render(<SectionTitle>Playback</SectionTitle>);
    const heading = screen.getByRole('heading', { level: 1, name: 'Playback' });
    expect(heading).toHaveAttribute('tabindex', '-1');
    expect(heading).toHaveAttribute('id', 'admin-section-title');
    heading.focus();
    expect(heading).toHaveFocus();
  });
});
