import { render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { christmasFixture } from '../../test/christmasFixture.js';
import ChristmasList from './ChristmasList.jsx';

const row = (title) => screen.getByRole('listitem', { name: title });

describe('ChristmasList: the In tab', () => {
  it('numbers the rows and writes "Title · year" with a reason chip', () => {
    const { inWeek } = christmasFixture();
    render(<ChristmasList rows={inWeek} tab="inWeek" max={12} />);
    expect(row('Die Hard')).toHaveTextContent('6');
    expect(row('Die Hard')).toHaveTextContent('Die Hard · 1988');
    expect(row('Die Hard')).toHaveTextContent('TMDB keyword');
    expect(row('Trading Places')).toHaveTextContent('Added by me');
  });

  it('draws the poster from a TMDB path or a stored art path, and a colour block when there is none', () => {
    render(<ChristmasList rows={christmasFixture().inWeek} tab="inWeek" max={12} />);
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
    const { rerender } = render(<ChristmasList rows={[first, sibling]} tab="inWeek" max={12} />);
    expect(row(first.title)).toBeInTheDocument();
    expect(row('Same TMDB id')).toBeInTheDocument();
    rerender(<ChristmasList rows={[sibling, first]} tab="inWeek" max={12} />);
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
    expect(error).not.toHaveBeenCalled();
    error.mockRestore();
  });

  it('has no cut line at exactly 12 rows', () => {
    const rows = christmasFixture().inWeek.slice(0, 12);
    render(<ChristmasList rows={rows} tab="inWeek" max={12} />);
    expect(screen.getAllByRole('listitem')).toHaveLength(12);
    expect(screen.queryByText(/Home shows 12\. These/)).not.toBeInTheDocument();
    expect(row('Elf')).not.toHaveClass('xmas-row--cut');
  });

  it('draws the cut line after row 12 and dims the rows below it', () => {
    render(<ChristmasList rows={christmasFixture().inWeek} tab="inWeek" max={12} />);
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
    render(<ChristmasList rows={rows} tab="inWeek" max={12} />);
    expect(screen.getByText(/These 1 are in/)).toBeInTheDocument();
  });

  it('shows no buttons while the rows have no actions', () => {
    render(<ChristmasList rows={christmasFixture().inWeek} tab="inWeek" max={12} />);
    expect(within(row('Die Hard')).queryByRole('button')).not.toBeInTheDocument();
  });
});

describe('ChristmasList: the Added and Left out tabs', () => {
  it('does not number the rows or cut them, and says why an added film is in', () => {
    render(<ChristmasList rows={christmasFixture().added} tab="added" max={12} />);
    expect(row('Trading Places')).not.toHaveTextContent('4');
    expect(row('Trading Places')).toHaveTextContent(
      "TMDB doesn't tag it, so it's only in because you added it.",
    );
  });

  it('explains a left-out film by whether TMDB tags it', () => {
    render(<ChristmasList rows={christmasFixture().leftOut} tab="leftOut" max={12} />);
    expect(row('Iron Man 3')).toHaveTextContent('Left out by me');
    expect(row('Iron Man 3')).toHaveTextContent(
      'TMDB tags it “christmas”. Put back and it shows again.',
    );
    expect(row('Rare Exports')).toHaveTextContent(
      "TMDB doesn't tag it, so Put back takes it off this screen.",
    );
  });
});
