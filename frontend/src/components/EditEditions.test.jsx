import { useState } from 'react';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import EditEditions from './EditEditions.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const FILM = { id: 40, title: 'The Lord of the Rings: The Two Towers', year: 2002 };
const PT1 = 'The Lord of the Rings - The Two Towers {edition-Extended Edition} - pt1.m4v';
const PT2 = 'The Lord of the Rings - The Two Towers {edition-Extended Edition} - pt2.m4v';
const THEATRICAL = 'The Lord of the Rings - The Two Towers {edition-Theatrical Cut}.m4v';

const isGet = (m, url) => m === 'GET' && url === '/api/admin/films/40/editions';
const isPut = (m, url) => m === 'PUT' && url === '/api/admin/films/40/editions';
const putOf = (fetchMock) => fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT');

const row = (name) => screen.getByRole('group', { name });
const editionSelect = (name) => within(row(name)).getByRole('combobox', { name: 'Edition' });
const partSelect = (name) => within(row(name)).getByRole('combobox', { name: 'Part' });
const saveButton = () => screen.getByRole('button', { name: /^Sav/ });

async function open(props = {}) {
  const onClose = vi.fn();
  const onSaved = vi.fn();
  const user = userEvent.setup();
  render(<EditEditions film={FILM} onClose={onClose} onSaved={onSaved} {...props} />);
  await screen.findByRole('region', { name: 'Edition Extended Edition' });
  return { user, onClose, onSaved };
}

describe('Edit editions: opening', () => {
  it('shows the header at once, a static skeleton, a disabled Save and focus on Close while loading', async () => {
    installFetch((m, url) => (isGet(m, url) ? new Promise(() => {}) : null));
    render(<EditEditions film={FILM} onClose={vi.fn()} onSaved={vi.fn()} />);

    const dialog = screen.getByRole('dialog', {
      name: 'The Lord of the Rings: The Two Towers · 2002',
    });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(within(dialog).getByText('Edit editions')).toBeInTheDocument();
    expect(within(dialog).getByRole('status', { name: 'Loading files' })).toHaveAttribute(
      'aria-busy',
      'true',
    );
    expect(within(dialog).getByText('Loading files…')).toBeInTheDocument();
    expect(saveButton()).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Close' })).toHaveFocus();
  });

  it('leaves the year out of the title when the film has none', async () => {
    installFetch((m, url) => (isGet(m, url) ? new Promise(() => {}) : null));
    render(
      <EditEditions
        film={{ id: 40, title: 'Heat', year: null }}
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />,
    );

    expect(screen.getByRole('dialog', { name: 'Heat' })).toBeInTheDocument();
  });

  it('applies the suggestion: groups with totals, the reason, rows with their facts, focus on the first Edition', async () => {
    installFetch();
    await open();

    expect(screen.getByText('Suggested')).toBeInTheDocument();
    expect(screen.getByText('From the edition tags and part numbers')).toBeInTheDocument();
    const extended = screen.getByRole('region', { name: 'Edition Extended Edition' });
    expect(within(extended).getByText('2 parts · 3h 55m')).toBeInTheDocument();
    expect(within(extended).getByText('1h 54m · 1080p · HEVC · 14.2 GB')).toBeInTheDocument();
    expect(within(extended).getByText('2h 1m · 1080p · HEVC · 15.0 GB')).toBeInTheDocument();
    const theatrical = screen.getByRole('region', { name: 'Edition Theatrical Cut' });
    expect(within(theatrical).getByText('single file · 2h 59m')).toBeInTheDocument();
    expect(editionSelect(PT1)).toHaveValue('e0');
    expect(partSelect(PT2)).toHaveValue('2');
    expect(partSelect(THEATRICAL)).toHaveValue('single');
    expect(
      within(partSelect(PT1))
        .getAllByRole('option')
        .map((o) => o.textContent),
    ).toEqual([
      'Single file',
      'Part 1',
      'Part 2',
      'Part 3',
      'Part 4',
      'Part 5',
      'Part 6',
      'Part 7',
      'Part 8',
    ]);
    await waitFor(() => expect(editionSelect(PT1)).toHaveFocus());
    expect(
      screen.getByText('Resume positions and watched state are kept for each edition.'),
    ).toBeInTheDocument();
    expect(saveButton()).toBeEnabled();
  });

  it('cuts a long file name in the middle, always keeping its last 22 characters, with the full name on hover and focus', async () => {
    installFetch();
    await open();

    const name = screen.getByLabelText(`File ${PT1}`);
    expect(name).toHaveAttribute('title', PT1);
    expect(name).toHaveAttribute('tabindex', '0');
    expect(within(name).getByText('ded Edition} - pt1.m4v')).toBeInTheDocument();
    await userEvent.tab();
    name.focus();
    expect(name).toHaveFocus();
  });

  it('lists the editions after saving with the default selected and not amber-marked', async () => {
    installFetch();
    await open();

    const defaults = screen.getByRole('group', { name: 'Default edition' });
    const radios = within(defaults).getAllByRole('radio');
    expect(radios).toHaveLength(2);
    expect(within(defaults).getByRole('radio', { name: /Theatrical Cut/ })).toBeChecked();
    expect(within(defaults).getByRole('radio', { name: /Extended Edition/ })).not.toBeChecked();
    expect(within(defaults).getAllByText('Default edition')).toHaveLength(2); // the legend and the chosen row
    expect(within(defaults).getByText('2 parts · 3h 55m')).toBeInTheDocument();
  });

  it('marks a missing file, and leaves out the Suggested line when there is nothing to say', async () => {
    installFetch((m, url) =>
      isGet(m, url)
        ? respond({
            files: [
              { ...fixtures.adminEditions.files[0], id: 1, editionId: 1, fileName: 'Heat.m4v' },
              {
                ...fixtures.adminEditions.files[1],
                id: 2,
                editionId: 2,
                fileName: 'Heat (1995) - copy.m4v',
                presence: 'MISSING',
              },
            ],
            editions: [],
            suggestion: {
              reason: '',
              editions: [
                { id: 1, name: 'Original', files: [{ fileId: 1, part: null }] },
                { id: 2, name: 'Original 2', files: [{ fileId: 2, part: null }] },
              ],
              notThisFilm: [],
              defaultName: 'Original',
            },
          })
        : null,
    );
    render(
      <EditEditions
        film={{ id: 40, title: 'Heat', year: 1995 }}
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />,
    );

    await screen.findByRole('region', { name: 'Edition Original 2' });
    expect(within(row('Heat (1995) - copy.m4v')).getByText('Missing')).toBeInTheDocument();
    expect(within(row('Heat.m4v')).queryByText('Missing')).not.toBeInTheDocument();
    expect(screen.queryByText('Suggested')).not.toBeInTheDocument();
  });

  it('says so, with Try again, when the files cannot be loaded', async () => {
    let fail = true;
    installFetch((m, url) => (isGet(m, url) && fail ? respond({ error: 'x' }, 500) : null));
    const user = userEvent.setup();
    render(<EditEditions film={FILM} onClose={vi.fn()} onSaved={vi.fn()} />);

    expect(await screen.findByText('Couldn’t load this film’s files.')).toBeInTheDocument();
    expect(saveButton()).toBeDisabled();
    fail = false;
    await user.click(screen.getByRole('button', { name: 'Try again' }));

    expect(
      await screen.findByRole('region', { name: 'Edition Extended Edition' }),
    ).toBeInTheDocument();
  });
});

describe('Edit editions: editing', () => {
  it('regroups a row at once when its edition changes, and the headers and totals follow', async () => {
    installFetch();
    const { user } = await open();

    await user.selectOptions(editionSelect(PT2), 'Theatrical Cut');

    const extended = screen.getByRole('region', { name: 'Edition Extended Edition' });
    expect(within(extended).getByText('1 part · 1h 54m')).toBeInTheDocument();
    expect(within(extended).queryByRole('group', { name: PT2 })).not.toBeInTheDocument();
    const theatrical = screen.getByRole('region', { name: 'Edition Theatrical Cut' });
    expect(within(theatrical).getByText('2 parts · 5h 0m')).toBeInTheDocument();
    expect(within(theatrical).getByRole('group', { name: PT2 })).toBeInTheDocument();
    // Theatrical now holds a single-file row beside a part: the rule says so beside the group.
    expect(
      within(theatrical).getByText(
        'Single file only works when an edition has one file — give each file a part number.',
      ),
    ).toBeInTheDocument();
    expect(screen.getByText('Fix 1 problem to save.')).toBeInTheDocument();
    expect(saveButton()).toBeDisabled();
  });

  it('keeps focus on the Edition select the person is using when its row moves to another group', async () => {
    installFetch();
    const { user } = await open();

    editionSelect(PT2).focus();
    await user.selectOptions(editionSelect(PT2), 'Theatrical Cut');

    expect(editionSelect(PT2)).toHaveFocus();
    expect(
      within(screen.getByRole('region', { name: 'Edition Theatrical Cut' })).getByRole('group', {
        name: PT2,
      }),
    ).toBeInTheDocument();
  });

  it('a part gap is said under the group header, tied to the Part selects, and counted in the footer', async () => {
    installFetch();
    const { user } = await open();

    await user.selectOptions(partSelect(PT2), 'Part 3');

    const extended = screen.getByRole('region', { name: 'Edition Extended Edition' });
    expect(within(extended).getByRole('alert')).toHaveTextContent(
      'Parts 1 and 3 — part 2 is missing.',
    );
    expect(partSelect(PT2)).toHaveAccessibleDescription('Parts 1 and 3 — part 2 is missing.');
    expect(partSelect(PT2)).toBeInvalid();
    expect(screen.getByText('Fix 1 problem to save.')).toBeInTheDocument();
    expect(saveButton()).toBeDisabled();
  });

  it('says two files are both part N, and that one file wants Single file or Part 1', async () => {
    installFetch();
    const { user } = await open();

    await user.selectOptions(partSelect(PT2), 'Part 1');
    expect(screen.getByText('Two files are both part 1.')).toBeInTheDocument();

    await user.selectOptions(partSelect(PT2), 'Part 2');
    await user.selectOptions(partSelect(THEATRICAL), 'Part 2');
    expect(
      screen.getByText('Only one file — set it to Single file or Part 1.'),
    ).toBeInTheDocument();
    await user.selectOptions(partSelect(THEATRICAL), 'Part 1');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(saveButton()).toBeEnabled();
  });

  it('a new edition gets a named field with a live counter, and its name errors sit beside it', async () => {
    installFetch();
    const { user } = await open();

    await user.selectOptions(editionSelect(THEATRICAL), 'New edition…');
    const field = within(row(THEATRICAL)).getByRole('textbox', { name: 'New edition name' });
    expect(field).toHaveAttribute('maxlength', '32');
    expect(within(row(THEATRICAL)).getByText('0/32')).toBeInTheDocument();
    expect(within(row(THEATRICAL)).getByRole('alert')).toHaveTextContent(
      'Give this edition a name.',
    );
    expect(field).toBeInvalid();

    await user.type(field, 'extended edition');
    expect(within(row(THEATRICAL)).getByText('16/32')).toBeInTheDocument();
    expect(field).toHaveAccessibleDescription(
      'Another edition is already called Extended Edition.',
    );
    expect(screen.getByText('Fix 2 problems to save.')).toBeInTheDocument();

    await user.clear(field);
    await user.type(field, 'Cinema Cut');
    expect(within(row(THEATRICAL)).queryByRole('alert')).not.toBeInTheDocument();
    expect(field).not.toBeInvalid();
  });

  it('stops a new name at 32 characters', async () => {
    installFetch();
    const { user } = await open();

    await user.selectOptions(editionSelect(THEATRICAL), 'New edition…');
    await user.type(
      within(row(THEATRICAL)).getByRole('textbox', { name: 'New edition name' }),
      'x'.repeat(40),
    );

    expect(within(row(THEATRICAL)).getByText('32/32')).toBeInTheDocument();
  });

  it('removing the default edition asks for another, and choosing one clears the problem', async () => {
    installFetch();
    const { user } = await open();

    await user.click(within(row(THEATRICAL)).getByRole('button', { name: 'Not this film' }));

    const defaults = screen.getByRole('group', { name: 'Default edition' });
    expect(within(defaults).getByRole('alert')).toHaveTextContent('Choose a default edition.');
    expect(within(defaults).getAllByRole('radio')).toHaveLength(1);
    expect(within(defaults).getByRole('radio', { name: /Extended Edition/ })).not.toBeChecked();
    expect(
      within(defaults).getByRole('radio', { name: /Extended Edition/ }),
    ).toHaveAccessibleDescription('Choose a default edition.');
    expect(saveButton()).toBeDisabled();

    await user.click(within(defaults).getByRole('radio', { name: /Extended Edition/ }));
    expect(within(defaults).queryByRole('alert')).not.toBeInTheDocument();
    expect(within(defaults).getByRole('radio', { name: /Extended Edition/ })).toBeChecked();
    expect(saveButton()).toBeEnabled();
  });

  it('Not this film moves a file into its own dashed group and Move back returns it unchanged', async () => {
    installFetch();
    const { user } = await open();
    await user.selectOptions(partSelect(PT2), 'Part 3');

    await user.click(within(row(PT2)).getByRole('button', { name: 'Not this film' }));

    const notThis = screen.getByRole('region', { name: 'Not this film' });
    expect(
      within(notThis).getByText(
        'When you save, each of these becomes its own unmatched film. Fix it with Fix match in Admin.',
      ),
    ).toBeInTheDocument();
    expect(within(notThis).getByRole('group', { name: PT2 })).toBeInTheDocument();
    expect(within(notThis).getByText('2h 1m · 1080p · HEVC · 15.0 GB')).toBeInTheDocument();
    expect(
      within(screen.getByRole('region', { name: 'Edition Extended Edition' })).getByText(
        '1 part · 1h 54m',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText('Parts 1 and 3 — part 2 is missing.')).not.toBeInTheDocument();

    await user.click(within(notThis).getByRole('button', { name: 'Move back' }));

    expect(screen.queryByRole('region', { name: 'Not this film' })).not.toBeInTheDocument();
    expect(editionSelect(PT2)).toHaveValue('e0');
    expect(partSelect(PT2)).toHaveValue('3');
    expect(screen.getByText('Parts 1 and 3 — part 2 is missing.')).toBeInTheDocument();
  });

  it('keeping no file at all is an error', async () => {
    installFetch();
    const { user } = await open();

    for (const name of [PT1, PT2, THEATRICAL]) {
      await user.click(within(row(name)).getByRole('button', { name: 'Not this film' }));
    }

    expect(screen.getByText('Keep at least one file with this film.')).toBeInTheDocument();
    expect(screen.getByText('Fix 1 problem to save.')).toBeInTheDocument();
    expect(saveButton()).toBeDisabled();
  });
});

describe('Edit editions: saving', () => {
  it('sends the arrangement, then reports what the server answered', async () => {
    const fetchMock = installFetch();
    const { user, onSaved, onClose } = await open();

    await user.click(saveButton());

    await waitFor(() => expect(onSaved).toHaveBeenCalledWith({ filmId: 40, newFilmIds: [] }));
    const [url, init] = putOf(fetchMock);
    expect(url).toBe('/api/admin/films/40/editions');
    expect(init.headers['X-CineFilm']).toBe('1');
    expect(JSON.parse(init.body)).toEqual({
      editions: [
        {
          id: 101,
          name: 'Extended Edition',
          default: false,
          files: [
            { fileId: 11, part: 1 },
            { fileId: 12, part: 2 },
          ],
        },
        { id: 103, name: 'Theatrical Cut', default: true, files: [{ fileId: 13, part: null }] },
      ],
      notThisFilm: [],
    });
    expect(onSaved).toHaveBeenCalledTimes(1);
    expect(onClose).not.toHaveBeenCalled();
  });

  it('a renamed edition goes up with its id, so its history goes with it', async () => {
    const fetchMock = installFetch();
    const { user, onSaved } = await open();

    await user.selectOptions(editionSelect(THEATRICAL), 'New edition…');
    await user.type(
      within(row(THEATRICAL)).getByRole('textbox', { name: 'New edition name' }),
      'Cinema Cut',
    );
    expect(
      within(screen.getByRole('group', { name: 'Default edition' })).getByRole('alert'),
    ).toHaveTextContent('Choose a default edition.');
    await user.click(screen.getByRole('radio', { name: /Cinema Cut/ }));
    await user.click(saveButton());

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(JSON.parse(putOf(fetchMock)[1].body).editions[1]).toEqual({
      id: 103,
      name: 'Cinema Cut',
      default: true,
      files: [{ fileId: 13, part: null }],
    });
  });

  it('sends the files marked Not this film', async () => {
    const fetchMock = installFetch();
    const { user, onSaved } = await open();

    await user.click(within(row(PT2)).getByRole('button', { name: 'Not this film' }));
    await user.selectOptions(partSelect(PT1), 'Single file');
    await user.click(saveButton());

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    const body = JSON.parse(putOf(fetchMock)[1].body);
    expect(body.notThisFilm).toEqual([12]);
    expect(body.editions[0].files).toEqual([{ fileId: 11, part: null }]);
  });

  it('a save that fails keeps every edit, says why, and can be tried again', async () => {
    let answer = respond({ error: 'A scan is running' }, 409);
    installFetch((m, url) => (isPut(m, url) ? answer : null));
    const { user, onSaved } = await open();
    await user.selectOptions(editionSelect(THEATRICAL), 'New edition…');
    await user.type(
      within(row(THEATRICAL)).getByRole('textbox', { name: 'New edition name' }),
      'Cinema Cut',
    );
    await user.click(screen.getByRole('radio', { name: /Cinema Cut/ }));

    await user.click(saveButton());

    const banner = await screen.findByRole('alert');
    expect(banner).toHaveTextContent(
      'Couldn’t save. A library scan is running — try again when it finishes. Your changes are still here.',
    );
    expect(onSaved).not.toHaveBeenCalled();
    expect(editionSelect(THEATRICAL)).toHaveValue('__new');
    expect(within(row(THEATRICAL)).getByRole('textbox', { name: 'New edition name' })).toHaveValue(
      'Cinema Cut',
    );
    expect(saveButton()).toBeEnabled();

    answer = respond({ filmId: 40, newFilmIds: [] });
    await user.click(saveButton());
    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1));
  });

  it('any other failure says something went wrong and still keeps the edits', async () => {
    installFetch((m, url) => (isPut(m, url) ? respond({ error: 'boom' }, 500) : null));
    const { user } = await open();
    await user.selectOptions(partSelect(PT2), 'Part 3');
    await user.selectOptions(partSelect(PT2), 'Part 2');
    await user.selectOptions(partSelect(THEATRICAL), 'Single file');

    await user.click(saveButton());

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Couldn’t save. Something went wrong — try again.',
    );
    expect(partSelect(PT2)).toHaveValue('2');
  });

  it('a 400 puts the server’s field errors beside their controls until the next edit', async () => {
    installFetch((m, url) =>
      isPut(m, url)
        ? respond(
            {
              error: 'Another edition is already called Theatrical Cut.',
              errors: [
                {
                  field: 'editions[1].name',
                  message: 'Another edition is already called Theatrical Cut.',
                },
              ],
            },
            400,
          )
        : null,
    );
    const { user } = await open();

    await user.click(saveButton());

    await waitFor(() =>
      expect(screen.getByRole('region', { name: 'Edition Theatrical Cut' })).toHaveTextContent(
        'Another edition is already called Theatrical Cut.',
      ),
    );
    expect(screen.getAllByRole('alert')[0]).toHaveTextContent(
      'Couldn’t save. Another edition is already called Theatrical Cut. Your changes are still here.',
    );
    expect(screen.getByText('Fix 1 problem to save.')).toBeInTheDocument();
    expect(saveButton()).toBeDisabled();

    await user.selectOptions(partSelect(THEATRICAL), 'Part 1');
    expect(screen.queryByText('Fix 1 problem to save.')).not.toBeInTheDocument();
    expect(saveButton()).toBeEnabled();
  });

  it('locks every editing control while the save is on its way and sends what is on screen', async () => {
    let release;
    const fetchMock = installFetch((m, url) =>
      isPut(m, url)
        ? new Promise((resolve) => {
            release = () => resolve(respond({ filmId: 40, newFilmIds: [] }));
          })
        : null,
    );
    const { user, onSaved } = await open();
    await user.selectOptions(partSelect(PT2), 'Part 2');

    await user.click(saveButton());

    await screen.findByRole('button', { name: 'Saving…' });
    expect(editionSelect(PT1)).toBeDisabled();
    expect(partSelect(PT1)).toBeDisabled();
    expect(within(row(PT1)).getByRole('button', { name: 'Not this film' })).toBeDisabled();
    screen.getAllByRole('radio').forEach((radio) => expect(radio).toBeDisabled());
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeEnabled();
    await act(async () => release());
    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1));
    expect(JSON.parse(putOf(fetchMock)[1].body).editions[1].default).toBe(true);
  });

  it('cannot be saved twice at once', async () => {
    let release;
    const fetchMock = installFetch((m, url) =>
      isPut(m, url)
        ? new Promise((resolve) => {
            release = () => resolve(respond({ filmId: 40, newFilmIds: [] }));
          })
        : null,
    );
    const { user, onSaved } = await open();

    await user.click(saveButton());

    expect(await screen.findByRole('button', { name: 'Saving…' })).toBeDisabled();
    await act(async () => release());
    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1));
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(1);
  });
});

describe('Edit editions: closing, focus and the keyboard', () => {
  it('Cancel, the close button and Esc all close it, with no confirm prompt', async () => {
    installFetch();
    const confirm = vi.spyOn(window, 'confirm');
    const { user, onClose } = await open();
    await user.selectOptions(partSelect(PT2), 'Part 3');

    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    await user.click(screen.getByRole('button', { name: 'Close' }));
    await user.keyboard('{Escape}');

    expect(onClose).toHaveBeenCalledTimes(3);
    expect(confirm).not.toHaveBeenCalled();
    confirm.mockRestore();
  });

  it('Esc closes it while the files are still loading, from anywhere', async () => {
    installFetch((m, url) => (isGet(m, url) ? new Promise(() => {}) : null));
    const onClose = vi.fn();
    render(<EditEditions film={FILM} onClose={onClose} onSaved={vi.fn()} />);

    await userEvent.keyboard('{Escape}');

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('keeps Tab inside the dialog, wrapping from the last control to the first and back', async () => {
    installFetch();
    const { user } = await open();
    const close = screen.getByRole('button', { name: 'Close' });

    saveButton().focus();
    await user.tab();
    expect(close).toHaveFocus();
    await user.tab({ shift: true });
    expect(saveButton()).toHaveFocus();
  });

  it('pulls focus back in when it was somewhere outside', async () => {
    installFetch();
    const { user } = await open();

    act(() => document.activeElement.blur());
    await user.tab();
    expect(screen.getByRole('button', { name: 'Close' })).toHaveFocus();

    act(() => document.activeElement.blur());
    await user.tab({ shift: true });
    expect(saveButton()).toHaveFocus();
  });

  it('tabs through a row in order: name, Edition, Part, Not this film', async () => {
    installFetch();
    const { user } = await open();
    const first = within(row(PT1));

    first.getByLabelText(`File ${PT1}`).focus();
    await user.tab();
    expect(first.getByRole('combobox', { name: 'Edition' })).toHaveFocus();
    await user.tab();
    expect(first.getByRole('combobox', { name: 'Part' })).toHaveFocus();
    await user.tab();
    expect(first.getByRole('button', { name: 'Not this film' })).toHaveFocus();
  });

  it('returns focus to the button that opened it, and puts the page scroll back', async () => {
    installFetch();
    function Opener() {
      const [shown, setShown] = useState(false);
      return (
        <>
          <button type="button" onClick={() => setShown(true)}>
            Edit editions…
          </button>
          {shown ? (
            <EditEditions
              film={FILM}
              onClose={() => setShown(false)}
              onSaved={() => setShown(false)}
            />
          ) : null}
        </>
      );
    }
    const user = userEvent.setup();
    render(<Opener />);
    const opener = screen.getByRole('button', { name: 'Edit editions…' });

    await user.click(opener);
    await screen.findByRole('region', { name: 'Edition Extended Edition' });
    expect(document.body.style.overflow).toBe('hidden');
    await user.keyboard('{Escape}');

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(opener).toHaveFocus();
    expect(document.body.style.overflow).toBe('');
  });

  it('returns focus to the opener it was given', async () => {
    installFetch();
    const other = document.createElement('button');
    document.body.appendChild(other);
    const view = render(
      <EditEditions film={FILM} opener={other} onClose={vi.fn()} onSaved={vi.fn()} />,
    );
    await screen.findByRole('region', { name: 'Edition Extended Edition' });

    view.unmount();

    expect(other).toHaveFocus();
    other.remove();
  });

  it('does not try to focus an opener that has gone from the page', async () => {
    installFetch();
    const gone = document.createElement('button');
    const view = render(
      <EditEditions film={FILM} opener={gone} onClose={vi.fn()} onSaved={vi.fn()} />,
    );
    await screen.findByRole('region', { name: 'Edition Extended Edition' });

    expect(() => view.unmount()).not.toThrow();
  });
});

describe('Edit editions: motion', () => {
  it('fades out for about 150 ms before it closes', async () => {
    installFetch();
    const onClose = vi.fn();
    const user = userEvent.setup();
    render(<EditEditions film={FILM} onClose={onClose} onSaved={vi.fn()} />);
    await screen.findByRole('region', { name: 'Edition Extended Edition' });
    vi.stubGlobal('matchMedia', () => ({ matches: false }));
    const timers = vi.spyOn(window, 'setTimeout');

    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    const fade = timers.mock.calls.find(([, ms]) => ms === 150);
    expect(fade).toBeDefined();
    expect(onClose).not.toHaveBeenCalled();
    act(() => fade[0]());
    expect(onClose).toHaveBeenCalledTimes(1);
    timers.mockRestore();
  });

  it('with reduced motion it closes at once', async () => {
    vi.stubGlobal('matchMedia', () => ({ matches: true }));
    installFetch();
    const { user, onClose } = await open();

    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
