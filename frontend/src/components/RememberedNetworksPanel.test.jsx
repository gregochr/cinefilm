import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NETWORKS_KEY } from '../state/networks.js';
import { installStorage } from '../test/storage.js';
import RememberedNetworksPanel from './RememberedNetworksPanel.jsx';

const VIRGIN = 'AS5089 Virgin Media Limited';
const RAIL = 'AS64500 Example Rail';
const LINE = "Forget makes CineFilm ask where you're watching next time on that network.";

let storage;
beforeEach(() => {
  storage = installStorage();
});
afterEach(() => vi.restoreAllMocks());

const remember = (list) => storage.setItem(NETWORKS_KEY, JSON.stringify(list));

describe('RememberedNetworksPanel', () => {
  it('says so, with no list and no Forget line, when nothing is remembered', () => {
    render(<RememberedNetworksPanel />);
    const card = screen.getByRole('region', { name: 'Remembered networks' });
    expect(within(card).getByText('No networks remembered yet.')).toBeInTheDocument();
    expect(within(card).queryByRole('list')).not.toBeInTheDocument();
    expect(within(card).queryByText(LINE)).not.toBeInTheDocument();
  });

  it('lists the networks newest first with the environment and the operator, and the owner’s line under them', () => {
    remember([
      { operator: VIRGIN, environment: 'away' },
      { operator: RAIL, environment: 'train' },
    ]);
    render(<RememberedNetworksPanel />);
    const [newest, oldest] = within(
      screen.getByRole('list', { name: 'Remembered networks' }),
    ).getAllByRole('listitem');
    expect(newest).toHaveTextContent('Train · Example Rail');
    expect(oldest).toHaveTextContent('Away · Virgin Media Limited');
    expect(
      screen.getByText('This browser remembers where you said you were watching'),
    ).toBeInTheDocument();
    expect(screen.getByText(LINE)).toBeInTheDocument();
  });

  it('gives each Forget a secondary button (44px tall by the shared .btn rule) named after its network', () => {
    remember([{ operator: VIRGIN, environment: 'away' }]);
    render(<RememberedNetworksPanel />);
    const forget = screen.getByRole('button', { name: 'Forget Virgin Media Limited' });
    expect(forget).toHaveTextContent('Forget');
    expect(forget).toHaveClass('btn');
    expect(forget).not.toHaveClass('btn--primary');
  });

  it('forgets a network: the row leaves, the other stays, storage is updated and focus moves to the heading', async () => {
    remember([
      { operator: VIRGIN, environment: 'away' },
      { operator: RAIL, environment: 'train' },
    ]);
    render(<RememberedNetworksPanel />);
    await userEvent.click(screen.getByRole('button', { name: 'Forget Example Rail' }));
    const items = within(screen.getByRole('list', { name: 'Remembered networks' })).getAllByRole(
      'listitem',
    );
    expect(items).toHaveLength(1);
    expect(items[0]).toHaveTextContent('Virgin Media Limited');
    expect(JSON.parse(storage.getItem(NETWORKS_KEY))).toEqual([
      { operator: VIRGIN, environment: 'away' },
    ]);
    expect(screen.getByRole('heading', { name: 'Remembered networks' })).toHaveFocus();
  });

  it('shows the empty line once the last network is forgotten', async () => {
    remember([{ operator: RAIL, environment: 'train' }]);
    render(<RememberedNetworksPanel />);
    await userEvent.click(screen.getByRole('button', { name: 'Forget Example Rail' }));
    expect(screen.getByText('No networks remembered yet.')).toBeInTheDocument();
    expect(screen.queryByText(LINE)).not.toBeInTheDocument();
  });

  it('keeps the row and says so beside it when the browser refuses the change', async () => {
    remember([{ operator: RAIL, environment: 'train' }]);
    render(<RememberedNetworksPanel />);
    vi.spyOn(storage, 'setItem').mockImplementation(() => {
      throw new Error('full');
    });
    const forget = screen.getByRole('button', { name: 'Forget Example Rail' });
    await userEvent.click(forget);
    expect(screen.getByRole('alert')).toHaveTextContent('Couldn’t forget that network. Try again.');
    expect(screen.getByRole('alert')).toHaveClass('plog-error');
    expect(screen.getByRole('listitem')).toHaveTextContent('Train · Example Rail');
    expect(forget).toHaveFocus();
  });

  it('shows the stored operator itself when stripping its AS number leaves no name', () => {
    remember([{ operator: 'AS5089 ', environment: 'train' }]);
    render(<RememberedNetworksPanel />);
    expect(screen.getByRole('button', { name: 'Forget AS5089' })).toBeInTheDocument();
    expect(screen.getByRole('listitem')).toHaveTextContent('Train · AS5089');
  });

  it('shows an operator name with no AS number as it is', () => {
    remember([{ operator: 'Home broadband', environment: 'away' }]);
    render(<RememberedNetworksPanel />);
    expect(screen.getByRole('listitem')).toHaveTextContent('Away · Home broadband');
  });
});
