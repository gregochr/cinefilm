import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { christmasFixture } from '../../test/christmasFixture.js';
import ChristmasList from './ChristmasList.jsx';

const row = (title) => screen.getByRole('listitem', { name: title });

/** The list with inert actions, for the tests about what a row shows. */
function List(props) {
  return (
    <ChristmasList
      max={12}
      busy={new Set()}
      onAct={vi.fn()}
      registerButton={() => null}
      {...props}
    />
  );
}

describe('ChristmasList: the In tab', () => {
  it('numbers the rows and writes "Title · year" with a reason chip', () => {
    const { inWeek } = christmasFixture();
    render(<List rows={inWeek} tab="inWeek" max={12} />);
    expect(row('Die Hard')).toHaveTextContent('6');
    expect(row('Die Hard')).toHaveTextContent('Die Hard · 1988');
    expect(row('Die Hard')).toHaveTextContent('TMDB keyword');
    expect(row('Trading Places')).toHaveTextContent('Added by me');
  });

  it('draws the poster from a TMDB path or a stored art path, and a colour block when there is none', () => {
    render(<List rows={christmasFixture().inWeek} tab="inWeek" />);
    expect(within(row("It's a Wonderful Life")).getByAltText('')).toHaveAttribute(
      'src',
      'https://image.tmdb.org/t/p/w342/abc.jpg',
    );
    expect(within(row('Miracle on 34th Street')).getByAltText('')).toHaveAttribute(
      'src',
      '/api/art/abc123.jpg?w=342',
    );
    expect(within(row('White Christmas')).queryByAltText('')).not.toBeInTheDocument();
  });

  it('keys rows by film, so two films sharing a TMDB id both show and React warns of nothing', () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    const [first] = christmasFixture().inWeek;
    const sibling = { ...first, filmId: 999, title: 'Same TMDB id', reason: 'ADDED' };
    const { rerender } = render(<List rows={[first, sibling]} tab="inWeek" />);
    expect(row(first.title)).toBeInTheDocument();
    expect(row('Same TMDB id')).toBeInTheDocument();
    rerender(<List rows={[sibling, first]} tab="inWeek" />);
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
    expect(error).not.toHaveBeenCalled();
    error.mockRestore();
  });

  it('has no cut line at exactly 12 rows', () => {
    const rows = christmasFixture().inWeek.slice(0, 12);
    render(<List rows={rows} tab="inWeek" max={12} />);
    expect(screen.getAllByRole('listitem')).toHaveLength(12);
    expect(screen.queryByText(/Home shows 12\. These/)).not.toBeInTheDocument();
    expect(row('Elf')).not.toHaveClass('xmas-row--cut');
  });

  it('draws the cut line after row 12 and dims the rows below it', () => {
    render(<List rows={christmasFixture().inWeek} tab="inWeek" max={12} />);
    const items = screen.getAllByRole('listitem');
    expect(items).toHaveLength(15);
    expect(items[12]).toHaveTextContent(
      "Home shows 12. These 2 are in, but won't fit. Leave out an older film to make room.",
    );
    expect(row('Elf')).not.toHaveClass('xmas-row--cut');
    expect(row('Love Actually')).toHaveClass('xmas-row--cut');
    expect(row('Arthur Christmas')).toHaveClass('xmas-row--cut');
  });

  it('counts a single film over the limit', () => {
    const rows = christmasFixture().inWeek.slice(0, 13);
    render(<List rows={rows} tab="inWeek" max={12} />);
    expect(screen.getByText(/These 1 are in/)).toBeInTheDocument();
  });
});

describe('ChristmasList: the Added and Left out tabs', () => {
  it('does not number the rows or cut them, and says why an added film is in', () => {
    render(<List rows={christmasFixture().added} tab="added" max={12} />);
    expect(row('Trading Places')).not.toHaveTextContent('4');
    expect(row('Trading Places')).toHaveTextContent(
      "TMDB doesn't tag it, so it's only in because you added it.",
    );
  });

  it('explains a left-out film by whether TMDB tags it', () => {
    render(<List rows={christmasFixture().leftOut} tab="leftOut" max={12} />);
    expect(row('Iron Man 3')).toHaveTextContent('Left out by me');
    expect(row('Iron Man 3')).toHaveTextContent(
      'TMDB tags it “christmas”. Put back and it shows again.',
    );
    expect(row('Rare Exports')).toHaveTextContent(
      "TMDB doesn't tag it, so Put back takes it off this screen.",
    );
  });
});

describe('ChristmasList: the row actions', () => {
  const setup = ({ rows, tab = 'inWeek', busy = new Set() }) => {
    const onAct = vi.fn();
    const refs = new Map();
    const registerButton = vi.fn((id) => (node) => {
      if (node) refs.set(id, node);
    });
    render(
      <ChristmasList
        rows={rows}
        tab={tab}
        max={12}
        busy={busy}
        onAct={onAct}
        registerButton={registerButton}
      />,
    );
    return { onAct, refs, registerButton };
  };

  it('gives each kind of row its one action', () => {
    setup({ rows: christmasFixture().inWeek });
    expect(within(row('Die Hard')).getAllByRole('button')).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Leave out Die Hard' })).toHaveTextContent(
      'Leave out',
    );
    expect(screen.getByRole('button', { name: 'Remove Trading Places' })).toHaveTextContent(
      'Remove',
    );
  });

  it('offers Put back on a left-out row', () => {
    setup({ rows: christmasFixture().leftOut, tab: 'leftOut' });
    expect(screen.getByRole('button', { name: 'Put back Iron Man 3' })).toHaveTextContent(
      'Put back',
    );
  });

  it('calls onAct with the row on a click, and on Enter from the keyboard', async () => {
    const data = christmasFixture();
    const { onAct } = setup({ rows: data.inWeek });
    await userEvent.click(screen.getByRole('button', { name: 'Leave out Die Hard' }));
    expect(onAct).toHaveBeenLastCalledWith(data.inWeek[5]);
    screen.getByRole('button', { name: 'Leave out Gremlins' }).focus();
    await userEvent.keyboard('{Enter}');
    expect(onAct).toHaveBeenLastCalledWith(data.inWeek[4]);
  });

  it('still works on the dimmed rows below the cut', async () => {
    const data = christmasFixture();
    const { onAct } = setup({ rows: data.inWeek });
    await userEvent.click(screen.getByRole('button', { name: 'Leave out Arthur Christmas' }));
    expect(onAct).toHaveBeenCalledWith(data.inWeek[13]);
  });

  it('holds only the busy row: it is aria-disabled and a click does nothing', async () => {
    const { onAct } = setup({ rows: christmasFixture().inWeek, busy: new Set([106]) });
    const held = screen.getByRole('button', { name: 'Leave out Die Hard' });
    expect(held).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(held);
    expect(onAct).not.toHaveBeenCalled();
    const other = screen.getByRole('button', { name: 'Leave out Gremlins' });
    expect(other).not.toHaveAttribute('aria-disabled');
    await userEvent.click(other);
    expect(onAct).toHaveBeenCalledTimes(1);
  });

  it('hands each row button to registerButton under the row filmId, even when films share a TMDB id', () => {
    const rows = christmasFixture().inWeek;
    const sibling = { ...rows[5], filmId: 99, title: 'Die Hard (TV)' };
    const { refs, registerButton } = setup({ rows: [...rows, sibling] });
    expect(registerButton).toHaveBeenCalledWith(6);
    expect(registerButton).toHaveBeenCalledWith(99);
    expect(refs.get(6)).toBe(screen.getByRole('button', { name: 'Leave out Die Hard' }));
    expect(refs.get(99)).toBe(screen.getByRole('button', { name: 'Leave out Die Hard (TV)' }));
  });
});
