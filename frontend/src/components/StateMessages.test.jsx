import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { EmptyLibrary, ErrorState, NoResults } from './StateMessages.jsx';

describe('EmptyLibrary', () => {
  it("invites the user to import from Plex or scan a folder, both leading to Admin's Scan now", () => {
    render(
      <MemoryRouter>
        <EmptyLibrary />
      </MemoryRouter>,
    );
    expect(screen.getByRole('heading', { name: 'The house lights are up' })).toBeInTheDocument();
    expect(screen.getByTestId('logo-mark')).toHaveAttribute('width', '64');
    expect(screen.getByRole('link', { name: 'Import from Plex' })).toHaveAttribute(
      'href',
      '/admin#library-scan',
    );
    expect(screen.getByRole('link', { name: 'Scan a folder' })).toHaveAttribute(
      'href',
      '/admin#library-scan',
    );
  });
});

describe('NoResults', () => {
  it('quotes the query and offers to clear it', async () => {
    const onClear = vi.fn();
    render(<NoResults query="zzz" onClear={onClear} />);
    expect(screen.getByRole('heading', { name: 'Nothing on the shelf' })).toBeInTheDocument();
    expect(screen.queryByTestId('logo-mark')).not.toBeInTheDocument();
    expect(screen.getByText(/No films match “zzz” with these filters/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Clear search and filters' }));
    expect(onClear).toHaveBeenCalledTimes(1);
  });

  it('does not mention a query when only filters are active', () => {
    render(<NoResults query="" onClear={() => {}} />);
    expect(screen.getByText(/^No films match with these filters/)).toBeInTheDocument();
    expect(screen.queryByText(/“/)).not.toBeInTheDocument();
  });
});

describe('ErrorState', () => {
  it('is an alert titled "The projector jammed" with a default message', () => {
    render(<ErrorState />);
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('The projector jammed');
    expect(alert).toHaveTextContent('Something went wrong loading this.');
    expect(within(alert).getByTestId('logo-mark')).toHaveAttribute('width', '48');
  });

  it('offers Try again only when it can retry', async () => {
    const { unmount } = render(<ErrorState />);
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
    unmount();
    const onRetry = vi.fn();
    render(<ErrorState onRetry={onRetry}>Could not reach the server.</ErrorState>);
    expect(screen.getByText('Could not reach the server.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});
