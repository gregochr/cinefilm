import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import AdminErrorBanner from './AdminErrorBanner.jsx';

describe('AdminErrorBanner', () => {
  it('is an alert with the words, no heading, and a Try again that retries', async () => {
    const onRetry = vi.fn();
    render(<AdminErrorBanner onRetry={onRetry}>Couldn’t load this.</AdminErrorBanner>);

    expect(screen.getByRole('alert')).toHaveTextContent('Couldn’t load this.');
    expect(screen.queryByRole('heading')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('takes its own retry label, and has no button without a retry', () => {
    const { rerender } = render(
      <AdminErrorBanner onRetry={() => {}} retryLabel="Check again">
        x
      </AdminErrorBanner>,
    );
    expect(screen.getByRole('button', { name: 'Check again' })).toBeInTheDocument();

    rerender(<AdminErrorBanner>x</AdminErrorBanner>);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('keeps the retry focusable but inert while busy', async () => {
    const onRetry = vi.fn();
    render(
      <AdminErrorBanner busy onRetry={onRetry}>
        x
      </AdminErrorBanner>,
    );
    const retry = screen.getByRole('button', { name: 'Try again' });
    expect(retry).toHaveAttribute('aria-disabled', 'true');
    expect(retry).toHaveAttribute('aria-busy', 'true');

    await userEvent.click(retry);

    expect(onRetry).not.toHaveBeenCalled();
  });

  it('announces the same words again when announceKey changes, keeping the same button', () => {
    const { rerender } = render(
      <AdminErrorBanner announceKey={1} onRetry={() => {}}>
        Same words
      </AdminErrorBanner>,
    );
    const words = screen.getByText('Same words');
    const retry = screen.getByRole('button', { name: 'Try again' });

    rerender(
      <AdminErrorBanner announceKey={2} onRetry={() => {}}>
        Same words
      </AdminErrorBanner>,
    );

    expect(screen.getByText('Same words')).not.toBe(words);
    expect(screen.getByRole('button', { name: 'Try again' })).toBe(retry);
  });
});
