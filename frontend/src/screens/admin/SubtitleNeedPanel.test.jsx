import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { forcedRow, fullRow, unlabelledRow } from '../../test/needRoutes.js';
import SubtitleNeedPanel from './SubtitleNeedPanel.jsx';

const need = (over = {}) => ({
  saving: {},
  problems: {},
  error: null,
  reload: vi.fn(),
  choose: vi.fn(() => Promise.resolve()),
  ...over,
});

function renderPanel({ kind = 'foreign', rows, active = true, ...rest }) {
  const props = { kind, rows, active, need: need(rest.need), onChanged: vi.fn() };
  const view = render(<SubtitleNeedPanel {...props} />);
  const rerender = (next) =>
    view.rerender(<SubtitleNeedPanel {...props} {...next} need={next.need ?? props.need} />);
  return { ...props, rerender };
}

describe('SubtitleNeedPanel', () => {
  it('is a tabpanel named by its tab, hidden when the other tab is open', () => {
    const { rerender } = renderPanel({ rows: [fullRow()] });
    const panel = screen.getByRole('tabpanel');
    expect(panel).toHaveAttribute('tabindex', '-1');

    rerender({ active: false });
    expect(screen.queryByRole('tabpanel')).not.toBeInTheDocument();
  });

  it('heads the rows with a count that says film or films', () => {
    const { rerender } = renderPanel({ rows: [fullRow(), unlabelledRow()] });
    expect(screen.getByRole('heading', { name: 'Need English subtitles' })).toBeInTheDocument();
    expect(screen.getByText('2 films')).toBeInTheDocument();

    rerender({ rows: [fullRow()] });
    expect(screen.getByText('1 film')).toBeInTheDocument();
  });

  it('says so when nothing is listed', () => {
    renderPanel({ kind: 'forced', rows: [] });

    expect(screen.getByText('0 films')).toBeInTheDocument();
    expect(
      screen.getByText('Nothing here. No English film is waiting for forced subtitles.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
  });

  it('shows a static skeleton until the list answers', () => {
    renderPanel({ rows: null });

    expect(
      screen.getByRole('status', { name: 'Loading the foreign-language films' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('heading')).not.toBeInTheDocument();
  });

  it('shows a banner with its own retry when the first read failed', async () => {
    const { need: shared } = renderPanel({
      kind: 'forced',
      rows: null,
      need: { error: new Error('x') },
    });

    expect(screen.getByRole('alert')).toHaveTextContent(
      'Couldn’t load the films with forced subtitles to find.',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(shared.reload).toHaveBeenCalledTimes(1);
  });

  it('keeps the rows and says so above them when a re-read failed', () => {
    renderPanel({ rows: [fullRow()], need: { error: new Error('x') } });

    expect(screen.getByRole('alert')).toHaveTextContent(
      'Couldn’t refresh the foreign-language films.',
    );
    expect(screen.getByText('Rashomon')).toBeInTheDocument();
  });

  it('sends NOT_NEEDED for the film once, however often its button is clicked while saving', async () => {
    let finish;
    const choose = vi.fn(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    renderPanel({ rows: [unlabelledRow()], need: { choose } });
    const button = screen.getByRole('button', { name: 'It’s English' });

    await userEvent.click(button);
    await userEvent.click(button);

    expect(choose).toHaveBeenCalledTimes(1);
    expect(choose).toHaveBeenCalledWith(8, 'NOT_NEEDED');
    finish();
  });

  it('does not send for a film whose choice the hook says is saving', async () => {
    const { need: shared } = renderPanel({
      rows: [unlabelledRow()],
      need: { saving: { 8: true } },
    });

    await userEvent.click(screen.getByRole('button', { name: 'It’s English' }));

    expect(shared.choose).not.toHaveBeenCalled();
  });

  it('shows each row’s problem on that row only', () => {
    renderPanel({
      rows: [unlabelledRow(), fullRow()],
      need: { problems: { 8: 'Couldn’t save: try again' } },
    });

    const rows = screen.getAllByRole('listitem');
    expect(within(rows[0]).getByRole('alert')).toBeInTheDocument();
    expect(within(rows[1]).queryByRole('alert')).not.toBeInTheDocument();
  });

  it('moves focus to the panel when the saved row has left and focus fell to the page', async () => {
    const row = forcedRow();
    const choose = vi.fn(() => Promise.resolve());
    const { rerender } = renderPanel({ kind: 'forced', rows: [row], need: { choose } });
    const button = screen.getByRole('button', { name: 'No foreign scenes' });
    button.focus();
    await userEvent.click(button);

    rerender({ rows: [] });

    expect(screen.getByRole('tabpanel')).toHaveFocus();
  });

  it('leaves focus where it is when the row leaves and focus is on something real', async () => {
    const choose = vi.fn(() => Promise.resolve());
    const { rerender } = render(
      <>
        <button type="button">Elsewhere</button>
        <SubtitleNeedPanel
          kind="forced"
          active
          rows={[forcedRow()]}
          need={need({ choose })}
          onChanged={vi.fn()}
        />
      </>,
    );
    await userEvent.click(screen.getByRole('button', { name: 'No foreign scenes' }));
    screen.getByRole('button', { name: 'Elsewhere' }).focus();

    rerender(
      <>
        <button type="button">Elsewhere</button>
        <SubtitleNeedPanel
          kind="forced"
          active
          rows={[]}
          need={need({ choose })}
          onChanged={vi.fn()}
        />
      </>,
    );

    expect(screen.getByRole('button', { name: 'Elsewhere' })).toHaveFocus();
  });

  it('does not move focus when the save failed and the row stayed', async () => {
    const row = forcedRow();
    const choose = vi.fn(() => Promise.resolve());
    const { rerender } = renderPanel({ kind: 'forced', rows: [row], need: { choose } });
    const button = screen.getByRole('button', { name: 'No foreign scenes' });
    await userEvent.click(button);

    rerender({
      rows: [{ ...row }],
      need: need({ choose, problems: { 2: 'Couldn’t save: try again' } }),
    });

    expect(screen.getByRole('tabpanel')).not.toHaveFocus();
    expect(button).toHaveFocus();
  });
});
