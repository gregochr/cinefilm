import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import AdminTodoNotice from './AdminTodoNotice.jsx';

describe('AdminTodoNotice', () => {
  it('is a labelled static skeleton while nothing has failed', () => {
    render(<AdminTodoNotice label="the films missing English subtitles" />);

    expect(
      screen.getByRole('status', { name: 'Loading the films missing English subtitles' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('is an alert with the message and a Try again that retries', async () => {
    const onRetry = vi.fn();
    render(<AdminTodoNotice label="x" error="Couldn’t load that." onRetry={onRetry} />);

    expect(screen.getByRole('alert')).toHaveTextContent('Couldn’t load that.');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('keeps Try again an ordinary button, never the amber one', () => {
    render(<AdminTodoNotice label="x" error="Nope" onRetry={() => {}} />);

    expect(screen.getByRole('button', { name: 'Try again' })).not.toHaveClass('btn--primary');
  });
});
