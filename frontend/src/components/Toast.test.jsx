import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import Toast from './Toast.jsx';

describe('Toast', () => {
  it('is an empty status region when there is nothing to say, so the next message is announced', () => {
    render(<Toast message={null} />);
    expect(screen.getByRole('status')).toBeEmptyDOMElement();
  });

  it('shows the message', () => {
    render(<Toast message="Editions saved" />);
    expect(screen.getByRole('status')).toHaveTextContent('Editions saved');
  });
});
