import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { fixtures } from '../../test/mockApi.jsx';
import LibraryRatingsCard from './LibraryRatingsCard.jsx';

function renderCard(props) {
  return render(
    <LibraryRatingsCard
      scan={fixtures.adminScan}
      starting={false}
      notice={null}
      onRefresh={() => {}}
      {...props}
    />,
  );
}

describe('LibraryRatingsCard', () => {
  it('draws nothing until the scan status has loaded', () => {
    const { container } = renderCard({ scan: undefined });
    expect(container).toBeEmptyDOMElement();
  });

  it('says when IMDb and Rotten Tomatoes were last updated, and refreshes on press', async () => {
    const onRefresh = vi.fn();
    renderCard({ onRefresh });

    expect(screen.getByRole('region', { name: 'Ratings' })).toBeInTheDocument();
    expect(screen.getByText('IMDb and Rotten Tomatoes · updated 8 Nov')).toBeInTheDocument();
    const refresh = screen.getByRole('button', { name: 'Refresh all ratings' });
    expect(refresh).toBeEnabled();
    expect(refresh).not.toHaveClass('btn--primary');
    expect(refresh).toHaveAccessibleDescription(
      'Re-fetches IMDb and Rotten Tomatoes scores for every film.',
    );
    await userEvent.click(refresh);
    expect(onRefresh).toHaveBeenCalledTimes(1);
  });

  it('says not fetched yet when no film has ratings', () => {
    renderCard({ scan: { ...fixtures.adminScan, ratingsLastRefreshedAt: null } });
    expect(screen.getByText('IMDb and Rotten Tomatoes · not fetched yet')).toBeInTheDocument();
  });

  it('asks for an OMDb key and turns the button off when ratings are off', () => {
    renderCard({ scan: { ...fixtures.adminScan, ratingsEnabled: false } });
    expect(screen.getByText('Ratings need an OMDb API key')).toBeInTheDocument();
    expect(screen.queryByText(/updated/)).toBeNull();
    expect(screen.getByRole('button', { name: 'Refresh all ratings' })).toBeDisabled();
  });

  it('shows Refreshing ratings… disabled while a refresh runs, and while the start request is in flight', () => {
    const { unmount } = renderCard({ scan: { ...fixtures.adminScan, ratingsRunning: true } });
    expect(screen.getByRole('button', { name: 'Refreshing ratings…' })).toBeDisabled();
    unmount();

    renderCard({ starting: true });
    expect(screen.getByRole('button', { name: 'Refresh all ratings' })).toBeDisabled();
  });

  it('shows why a refresh could not start', () => {
    renderCard({ notice: 'Could not start the ratings refresh.' });
    expect(screen.getByRole('status')).toHaveTextContent('Could not start the ratings refresh.');
  });
});
