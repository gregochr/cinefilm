import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client.js';
import adminEditions from '../test/fixtures/admin-editions.json';
import {
  NEW,
  PART_OPTIONS,
  buildRequest,
  describeModel,
  fileMeta,
  footNote,
  initialModel,
  saveProblem,
  setDefault,
  setEdition,
  setNewName,
  setNot,
  setPart,
  splitName,
} from './editEditions.js';

const towers = () => initialModel(adminEditions);

describe('fileMeta', () => {
  it('reads runtime, resolution, codec and size', () => {
    expect(fileMeta(adminEditions.files[0])).toBe('1h 54m · 1080p · HEVC · 14.2 GB');
    expect(fileMeta(adminEditions.files[1])).toBe('2h 1m · 1080p · HEVC · 15.0 GB');
  });

  it('leaves out what is unknown and shows small files in megabytes', () => {
    expect(fileMeta({ durationMs: 0, width: null, videoCodec: null, sizeBytes: null })).toBe('');
    expect(
      fileMeta({ durationMs: 45 * 60000, width: 1280, videoCodec: 'H.264', sizeBytes: 650e6 }),
    ).toBe('45m · 720p · H.264 · 650 MB');
  });
});

describe('splitName', () => {
  it('always keeps the last 22 characters whole', () => {
    const name = adminEditions.files[0].fileName;
    const { head, tail } = splitName(name);
    expect(tail).toBe('ded Edition} - pt1.m4v');
    expect(tail).toHaveLength(22);
    expect(head + tail).toBe(name);
  });

  it('does not cut a name of 22 characters or fewer, and cuts one of 23', () => {
    expect(splitName('a'.repeat(22))).toEqual({ head: '', tail: 'a'.repeat(22) });
    expect(splitName('b' + 'a'.repeat(22))).toEqual({ head: 'b', tail: 'a'.repeat(22) });
  });
});

describe('PART_OPTIONS', () => {
  it('is Single file then Part 1 to 8', () => {
    expect(PART_OPTIONS.map((o) => o.label)).toEqual([
      'Single file',
      ...[1, 2, 3, 4, 5, 6, 7, 8].map((n) => `Part ${n}`),
    ]);
    expect(PART_OPTIONS[0].value).toBe('single');
    expect(PART_OPTIONS[8].value).toBe('8');
  });
});

describe('initialModel', () => {
  it('applies the suggestion: each file in its edition and part, the default named', () => {
    const model = towers();

    expect(model.fixed).toEqual([
      { key: 'e0', id: 101, name: 'Extended Edition' },
      { key: 'e1', id: 103, name: 'Theatrical Cut' },
    ]);
    expect(model.rows[11]).toEqual({ edition: 'e0', newName: '', part: '1', not: false });
    expect(model.rows[12]).toEqual({ edition: 'e0', newName: '', part: '2', not: false });
    expect(model.rows[13]).toEqual({ edition: 'e1', newName: '', part: 'single', not: false });
    expect(model.def).toBe('Theatrical Cut');
    expect(model.reason).toBe('From the edition tags and part numbers');
    expect(model.current).toEqual({ 11: 101, 12: 102, 13: 103 });
  });

  it('puts the files the suggestion says are not this film in Not this film, back-able into the first edition', () => {
    const model = initialModel({
      ...adminEditions,
      suggestion: {
        ...adminEditions.suggestion,
        notThisFilm: [13],
        editions: [adminEditions.suggestion.editions[0]],
      },
    });

    expect(model.rows[13]).toEqual({ edition: 'e0', newName: '', part: 'single', not: true });
  });

  it('falls back to the first edition for the default, and to no edition when there are none', () => {
    const noDefault = initialModel({
      ...adminEditions,
      suggestion: { ...adminEditions.suggestion, defaultName: null },
    });
    expect(noDefault.def).toBe('Extended Edition');

    const none = initialModel({
      files: adminEditions.files,
      suggestion: { reason: '', editions: [], notThisFilm: [], defaultName: null },
    });
    expect(none.def).toBe('');
    expect(none.rows[11].edition).toBe(NEW);
  });
});

describe('edits', () => {
  it('put a file in an edition, new or existing, clearing any typed name', () => {
    let model = setNewName(setEdition(towers(), 13, NEW), 13, 'Cinema');
    expect(model.rows[13]).toMatchObject({ edition: NEW, newName: 'Cinema' });
    model = setEdition(model, 13, 'e0');
    expect(model.rows[13]).toMatchObject({ edition: 'e0', newName: '' });
  });

  it('keep a typed name to 32 characters', () => {
    const model = setNewName(setEdition(towers(), 13, NEW), 13, 'x'.repeat(40));
    expect(model.rows[13].newName).toBe('x'.repeat(32));
  });

  it('set the part, mark Not this film and back, and choose the default', () => {
    let model = setPart(towers(), 12, '3');
    expect(model.rows[12].part).toBe('3');
    model = setNot(model, 12, true);
    expect(model.rows[12].not).toBe(true);
    model = setNot(model, 12, false);
    expect(model.rows[12]).toMatchObject({ not: false, edition: 'e0', part: '3' });
    expect(setDefault(model, 'Extended Edition').def).toBe('Extended Edition');
  });

  it('never change the model they were given', () => {
    const model = towers();
    const before = JSON.stringify(model);
    setPart(model, 12, '3');
    setNot(model, 12, true);
    setEdition(model, 12, NEW);
    expect(JSON.stringify(model)).toBe(before);
  });
});

describe('buildRequest', () => {
  it('is the arrangement as the server takes it: groups in file order, parts, default, not this film', () => {
    expect(buildRequest(towers())).toEqual({
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
  });

  it('sorts a group by part and leaves out the files marked Not this film', () => {
    let model = setPart(setPart(towers(), 11, '2'), 12, '1');
    model = setNot(model, 13, true);
    const request = buildRequest(model);

    expect(request.editions).toHaveLength(1);
    expect(request.editions[0].files).toEqual([
      { fileId: 12, part: 1 },
      { fileId: 11, part: 2 },
    ]);
    expect(request.notThisFilm).toEqual([13]);
    expect(request.editions[0].default).toBe(false);
  });

  it('a new edition that takes over an emptied one is that edition renamed and keeps its id', () => {
    const model = setNewName(setEdition(towers(), 13, NEW), 13, ' Cinema Cut ');
    const request = buildRequest(setDefault(model, 'Cinema Cut'));

    expect(request.editions[1]).toEqual({
      id: 103,
      name: 'Cinema Cut',
      default: true,
      files: [{ fileId: 13, part: null }],
    });
  });

  it('a new edition made of files from several editions adopts the one holding most (lower id on a tie)', () => {
    let model = setEdition(setEdition(towers(), 11, NEW), 12, NEW);
    model = setNewName(setNewName(model, 11, 'Whole'), 12, 'Whole');
    model = setEdition(model, 13, 'e1');
    // 11 was in edition 101, 12 in 102: one file each, so the lower id wins and the other is left to be deleted.
    expect(buildRequest(model).editions[0]).toMatchObject({ id: 101, name: 'Whole' });
  });

  it('a new edition with no history stays new, and an edition already in use is not adopted twice', () => {
    let model = setEdition(setEdition(towers(), 13, NEW), 12, NEW);
    model = setNewName(setNewName(model, 13, 'A'), 12, 'B');
    const request = buildRequest(model);
    const ids = request.editions.map((e) => e.id);

    expect(ids).toEqual([101, 102, 103]);
    expect(new Set(ids).size).toBe(3);

    const none = setEdition(initialModel(adminEditions), 13, NEW);
    const fresh = { ...none, current: { 11: 101, 12: 102, 13: 101 } };
    expect(buildRequest(setNewName(fresh, 13, 'C')).editions[1].id).toBe(null);
  });

  it('a blank-named group is never the default', () => {
    const model = setDefault(setEdition(towers(), 13, NEW), '');
    const request = buildRequest(model);
    expect(request.editions[1].name).toBe('');
    expect(request.editions[1].default).toBe(false);
  });
});

describe('describeModel', () => {
  it('describes the typical arrangement: groups with summaries, radios, no errors', () => {
    const view = describeModel(towers());

    expect(view.errorCount).toBe(0);
    expect(view.groups.map((g) => [g.name, g.label, g.summary])).toEqual([
      ['Extended Edition', 'Edition Extended Edition', '2 parts · 3h 55m'],
      ['Theatrical Cut', 'Edition Theatrical Cut', 'single file · 2h 59m'],
    ]);
    expect(view.defaults).toEqual([
      { name: 'Extended Edition', meta: '2 parts · 3h 55m', on: false },
      { name: 'Theatrical Cut', meta: 'single file · 2h 59m', on: true },
    ]);
    expect(view.notRows).toEqual([]);
    expect(view.groups[0].rows.map((r) => r.file.id)).toEqual([11, 12]);
  });

  it('a lone file set to a part is "1 part"', () => {
    const view = describeModel(setPart(setNot(towers(), 12, true), 11, '1'));
    expect(view.groups[0].summary).toBe('1 part · 1h 54m');
  });

  it('regroups live: a file moved to another edition leaves the totals of both behind', () => {
    const view = describeModel(setEdition(towers(), 12, 'e1'));

    expect(view.groups[0].summary).toBe('1 part · 1h 54m');
    expect(view.groups[1].summary).toBe('2 parts · 5h 0m');
    expect(view.groups[1].error).toBe(
      'Single file only works when an edition has one file — give each file a part number.',
    );
    expect(view.groups[1].partsInError).toBe(true);
    expect(view.errorCount).toBe(1);
  });

  it('puts a name problem beside the new-name field, and a part problem under the heading', () => {
    const named = setNewName(setEdition(towers(), 13, NEW), 13, 'extended edition');
    const view = describeModel(named);

    expect(view.groups[1].rows[0].isNew).toBe(true);
    expect(view.groups[1].rows[0].nameError).toBe(
      'Another edition is already called Extended Edition.',
    );
    expect(view.groups[1].error).toBe('');
    expect(describeModel(setEdition(towers(), 13, NEW)).groups[1].rows[0].nameError).toBe(
      'Give this edition a name.',
    );
    expect(describeModel(setEdition(towers(), 13, NEW)).groups[1].name).toBe('(unnamed)');

    const parts = describeModel(setPart(towers(), 12, '3'));
    expect(parts.groups[0].error).toBe('Parts 1 and 3 — part 2 is missing.');
  });

  it('a name problem on a group with no new-name field is said under its heading', () => {
    const model = towers();
    const odd = { ...model, fixed: [model.fixed[0], { key: 'e1', id: 103, name: '' }] };
    expect(describeModel(odd).groups[1].error).toBe('Give this edition a name.');
  });

  it('says so when the default edition is gone or nothing is kept', () => {
    const gone = describeModel(setNot(towers(), 13, true));
    expect(gone.noDefault).toBe('Choose a default edition.');
    expect(gone.notRows.map((f) => f.id)).toEqual([13]);
    expect(gone.errorCount).toBe(1);

    let all = towers();
    [11, 12, 13].forEach((id) => {
      all = setNot(all, id, true);
    });
    const none = describeModel(all);
    expect(none.noEditions).toBe('Keep at least one file with this film.');
    expect(none.noDefault).toBe('');
    expect(none.defaults).toEqual([]);
  });

  it('counts every problem and keeps the server errors until they are cleared, without repeating its own', () => {
    const model = setPart(setPart(towers(), 12, '3'), 13, '2');
    const view = describeModel(model, [
      { field: 'editions[0].parts', message: 'Parts 1 and 3 — part 2 is missing.' },
      { field: 'files', message: 'Every file has to be in an edition or marked Not this film.' },
    ]);

    expect(view.errorCount).toBe(3);
    expect(view.other).toEqual(['Every file has to be in an edition or marked Not this film.']);
  });
});

describe('footNote', () => {
  it('says loading, how many problems to fix, or that state is kept', () => {
    expect(footNote(true, 0)).toBe('Loading files…');
    expect(footNote(false, 1)).toBe('Fix 1 problem to save.');
    expect(footNote(false, 2)).toBe('Fix 2 problems to save.');
    expect(footNote(false, 0)).toBe(
      'Resume positions and watched state are kept for each edition.',
    );
  });
});

describe('saveProblem', () => {
  it('names a running scan on a 409', () => {
    expect(saveProblem(new ApiError(409))).toEqual({
      text: 'A library scan is running — try again when it finishes. Your changes are still here.',
      fieldErrors: [],
    });
  });

  it('takes the first message and the field errors from a 400', () => {
    const errors = [
      { field: 'default', message: 'Choose a default edition.' },
      { field: 'editions', message: 'Keep at least one file with this film.' },
    ];
    expect(saveProblem(new ApiError(400, undefined, { error: 'x', errors }))).toEqual({
      text: 'Choose a default edition. Your changes are still here.',
      fieldErrors: errors,
    });
  });

  it('falls back to the generic line for anything else, a 400 with no field errors included', () => {
    const generic = { text: 'Something went wrong — try again.', fieldErrors: [] };
    expect(saveProblem(new ApiError(500))).toEqual(generic);
    expect(saveProblem(new ApiError(400))).toEqual(generic);
    expect(saveProblem(new ApiError(400, undefined, { error: 'x', errors: [] }))).toEqual(generic);
    expect(saveProblem(new Error('network'))).toEqual(generic);
    expect(saveProblem(undefined)).toEqual(generic);
  });
});
