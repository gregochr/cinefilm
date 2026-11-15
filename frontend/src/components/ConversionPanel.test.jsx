import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { fixtures } from '../test/mockApi.jsx';
import ConversionPanel from './ConversionPanel.jsx';

const ready = (over = {}) => ({
  transcoding: { ...structuredClone(fixtures.adminTranscoding), ...over },
  error: null,
  loading: false,
  reload: vi.fn(),
});

describe('ConversionPanel', () => {
  it('shows the encoder with its status, the seek index and the stream cache', () => {
    render(<ConversionPanel state={ready()} />);
    const card = screen.getByRole('region', { name: 'Conversion' });
    expect(within(card).getByText('VAAPI (hardware) · ready')).toBeInTheDocument();
    expect(within(card).getByText('Seek index').nextElementSibling).toHaveTextContent(
      '412 of 538 films',
    );
    expect(within(card).getByText('Stream cache').nextElementSibling).toHaveTextContent('1.2 GB');
  });

  it('does not show pre-made versions, which nothing in the API counts', () => {
    render(<ConversionPanel state={ready()} />);
    expect(screen.queryByText(/pre-made/i)).not.toBeInTheDocument();
  });

  it('says Unavailable, with no ready, when the server found no encoder', () => {
    render(<ConversionPanel state={ready({ encoder: 'unavailable' })} />);
    expect(screen.getByText('Unavailable')).toBeInTheDocument();
    expect(screen.queryByText(/ready/)).not.toBeInTheDocument();
  });

  it('shows a skeleton and no encoder while loading', () => {
    render(<ConversionPanel state={{ transcoding: null, error: null, loading: true }} />);
    expect(
      screen.getByRole('status', { name: 'Loading the conversion state' }),
    ).toBeInTheDocument();
    expect(screen.queryByText(/ready/)).not.toBeInTheDocument();
  });

  it('announces a failure and Try again reloads', async () => {
    const reload = vi.fn();
    render(
      <ConversionPanel
        state={{ transcoding: null, error: new Error('x'), loading: false, reload }}
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Couldn’t load the conversion state.');
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(reload).toHaveBeenCalledOnce();
  });

  it('keeps showing the last answer when a later read fails', () => {
    render(<ConversionPanel state={{ ...ready(), error: new Error('x') }} />);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByText('412 of 538 films')).toBeInTheDocument();
  });
});
