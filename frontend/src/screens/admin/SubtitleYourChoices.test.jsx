import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { forcedRow, fullRow } from '../../test/needRoutes.js';
import SubtitleYourChoices, { ELSE_HEADING_ID } from './SubtitleYourChoices.jsx';

const state = (rows, over = {}) => ({
  rows,
  saving: {},
  problems: {},
  choose: vi.fn(() => Promise.resolve()),
  ...over,
});

const chosenFull = (over = {}) =>
  fullRow({ filmId: 8, title: 'Downfall', year: 2004, ownerChoice: 'NOT_NEEDED', ...over });
const chosenForced = (over = {}) =>
  forcedRow({ filmId: 2, title: 'Taken', year: 2008, ownerChoice: 'NOT_NEEDED', ...over });

function renderChoices({ full = state([]), forced = state([]) } = {}) {
  const view = render(
    <>
      <h2 id={ELSE_HEADING_ID} tabIndex={-1}>
        Everything else
      </h2>
      <SubtitleYourChoices full={full} forced={forced} />
    </>,
  );
  const rerender = (next) =>
    view.rerender(
      <>
        <h2 id={ELSE_HEADING_ID} tabIndex={-1}>
          Everything else
        </h2>
        <SubtitleYourChoices full={next.full ?? full} forced={next.forced ?? forced} />
      </>,
    );
  return { full, forced, rerender };
}

describe('SubtitleYourChoices', () => {
  it('draws nothing when the owner has made no choice', () => {
    renderChoices({ full: state([fullRow({ ownerChoice: null })]), forced: state(null) });

    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('counts the choices of both lists on a quiet toggle and lists them when opened', async () => {
    renderChoices({ full: state([chosenFull()]), forced: state([chosenForced()]) });
    const toggle = screen.getByRole('button', { name: 'Your choices (2)' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('list', { name: 'Your choices' })).not.toBeInTheDocument();

    await userEvent.click(toggle);

    const list = screen.getByRole('list', { name: 'Your choices' });
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(toggle).toHaveAttribute('aria-controls', list.id);
    const [full, forced] = within(list).getAllByRole('listitem');
    expect(full).toHaveTextContent('Downfall (2004) · Full subtitles: you said not needed');
    expect(forced).toHaveTextContent('Taken (2008) · Forced subtitles: you said not needed');
  });

  it('says "you said needed" for an owner’s NEEDED', async () => {
    renderChoices({ full: state([chosenFull({ ownerChoice: 'NEEDED' })]) });

    await userEvent.click(screen.getByRole('button', { name: 'Your choices (1)' }));

    expect(screen.getByText(/Full subtitles: you said needed/)).toBeInTheDocument();
  });

  it('gives a choice back with null through that list’s own save, and nothing else', async () => {
    const { full, forced } = renderChoices({
      full: state([chosenFull()]),
      forced: state([chosenForced()]),
    });
    await userEvent.click(screen.getByRole('button', { name: 'Your choices (2)' }));

    await userEvent.click(
      screen.getByRole('button', { name: 'Let CineFilm decide: Taken (2008), forced subtitles' }),
    );

    expect(forced.choose).toHaveBeenCalledWith(2, null);
    expect(full.choose).not.toHaveBeenCalled();
  });

  it('does nothing on a second click while the first is in flight, and while the hook says it is saving', async () => {
    let finish;
    const choose = vi.fn(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const { full } = renderChoices({ full: state([chosenFull()], { choose }) });
    await userEvent.click(screen.getByRole('button', { name: 'Your choices (1)' }));
    const button = screen.getByRole('button', { name: /^Let CineFilm decide: Downfall/ });

    await userEvent.click(button);
    await userEvent.click(button);
    expect(choose).toHaveBeenCalledTimes(1);
    finish();
    expect(full.rows).toHaveLength(1);
  });

  it('is aria-disabled, and still focusable, while the list says the film is saving', async () => {
    const saving = state([chosenFull()], { saving: { 8: true } });
    renderChoices({ full: saving });
    await userEvent.click(screen.getByRole('button', { name: 'Your choices (1)' }));
    const button = screen.getByRole('button', { name: /^Let CineFilm decide: Downfall/ });

    await userEvent.click(button);

    expect(button).toHaveAttribute('aria-disabled', 'true');
    expect(saving.choose).not.toHaveBeenCalled();
  });

  it('shows a failed change on its row, in the words the list gives', async () => {
    renderChoices({
      full: state([chosenFull()], { problems: { 8: 'Couldn’t save: try again' } }),
    });
    await userEvent.click(screen.getByRole('button', { name: 'Your choices (1)' }));

    expect(screen.getByRole('alert')).toHaveTextContent('Couldn’t save: try again');
  });

  it('moves focus to the toggle when a row leaves and others stay', async () => {
    const choices = [chosenFull(), chosenFull({ filmId: 9, title: 'Ran' })];
    const { rerender } = renderChoices({ full: state(choices) });
    await userEvent.click(screen.getByRole('button', { name: 'Your choices (2)' }));
    const reset = screen.getByRole('button', { name: /^Let CineFilm decide: Downfall/ });
    reset.focus();
    await userEvent.click(reset);

    rerender({ full: state([choices[1]]) });

    expect(screen.getByRole('button', { name: 'Your choices (1)' })).toHaveFocus();
  });

  it('moves focus to the section heading when the last choice has been given back', async () => {
    const { rerender } = renderChoices({ full: state([chosenFull()]) });
    await userEvent.click(screen.getByRole('button', { name: 'Your choices (1)' }));
    const reset = screen.getByRole('button', { name: /^Let CineFilm decide: Downfall/ });
    reset.focus();
    await userEvent.click(reset);

    rerender({ full: state([chosenFull({ ownerChoice: null })]) });

    expect(screen.queryByRole('button', { name: /Your choices/ })).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Everything else' })).toHaveFocus();
  });

  it('leaves focus alone when the row leaves and focus is on something real', async () => {
    const { rerender } = render(
      <>
        <button type="button">Elsewhere</button>
        <SubtitleYourChoices
          full={state([chosenFull(), chosenFull({ filmId: 9 })])}
          forced={state([])}
        />
      </>,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Your choices (2)' }));
    await userEvent.click(screen.getAllByRole('button', { name: /^Let CineFilm decide/ })[0]);
    screen.getByRole('button', { name: 'Elsewhere' }).focus();

    rerender(
      <>
        <button type="button">Elsewhere</button>
        <SubtitleYourChoices full={state([chosenFull({ filmId: 9 })])} forced={state([])} />
      </>,
    );

    expect(screen.getByRole('button', { name: 'Elsewhere' })).toHaveFocus();
  });

  it('keeps focus where it is when the change failed and the row stayed', async () => {
    const choices = [chosenFull()];
    const { rerender } = renderChoices({ full: state(choices) });
    await userEvent.click(screen.getByRole('button', { name: 'Your choices (1)' }));
    const reset = screen.getByRole('button', { name: /^Let CineFilm decide: Downfall/ });
    await userEvent.click(reset);

    rerender({ full: state([{ ...choices[0] }], { problems: { 8: 'Couldn’t save: try again' } }) });

    expect(reset).toHaveFocus();
    expect(screen.getByRole('heading', { name: 'Everything else' })).not.toHaveFocus();
  });
});
