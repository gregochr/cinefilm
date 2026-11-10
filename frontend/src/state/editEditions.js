// The Edit editions panel's model: what the owner has arranged, turned into groups, a save request and errors.
// No JSX: the Apple TV and native clients reuse it. The rules themselves are in editionRules.js.
import { ApiError } from '../api/client.js';
import { formatRuntime } from '../player/time.js';
import { validateArrangement } from './editionRules.js';
import { resolutionLabel } from './resolution.js';

/** The Edition select's value for "New edition…". */
export const NEW = '__new';
/** How many characters at the end of a file name always stay visible when it is cut in the middle. */
export const TAIL = 22;
/** The unnamed group's heading while a new edition has no name yet. */
export const UNNAMED = '(unnamed)';

/** The Part select's choices: "Single file", then Part 1 to 8. */
export const PART_OPTIONS = [
  { value: 'single', label: 'Single file' },
  ...[1, 2, 3, 4, 5, 6, 7, 8].map((n) => ({ value: String(n), label: `Part ${n}` })),
];

function sizeLabel(bytes) {
  if (!bytes || bytes <= 0) return null;
  if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(1)} GB`;
  return `${Math.round(bytes / 1e6)} MB`;
}

/** "1h 54m · 1080p · HEVC · 14.2 GB", with what is unknown left out. */
export function fileMeta(file) {
  return [
    file.durationMs > 0 ? formatRuntime(file.durationMs) : null,
    resolutionLabel(file.width),
    file.videoCodec,
    sizeLabel(file.sizeBytes),
  ]
    .filter(Boolean)
    .join(' · ');
}

/** A file name cut in the middle: the head may be shortened by the browser, the last 22 characters never are. */
export function splitName(name) {
  return name.length > TAIL
    ? { head: name.slice(0, -TAIL), tail: name.slice(-TAIL) }
    : { head: '', tail: name };
}

/**
 * The panel's starting point: the suggestion applied. `fixed` are the editions a file can be put in (the
 * suggestion's), `rows` hold each file's choices, `current` says which edition each file is in today.
 */
export function initialModel(view) {
  const fixed = view.suggestion.editions.map((e, i) => ({ key: `e${i}`, id: e.id, name: e.name }));
  const rows = {};
  const first = fixed.length > 0 ? fixed[0].key : NEW;
  view.files.forEach((file) => {
    rows[file.id] = { edition: first, newName: '', part: 'single', not: false };
  });
  view.suggestion.editions.forEach((edition, i) => {
    edition.files.forEach((slot) => {
      rows[slot.fileId] = {
        edition: fixed[i].key,
        newName: '',
        part: slot.part == null ? 'single' : String(slot.part),
        not: false,
      };
    });
  });
  view.suggestion.notThisFilm.forEach((id) => {
    rows[id] = { ...rows[id], not: true };
  });
  const current = Object.fromEntries(view.files.map((f) => [f.id, f.editionId]));
  const def = view.suggestion.defaultName ?? fixed[0]?.name ?? '';
  return { files: view.files, fixed, rows, current, def, reason: view.suggestion.reason };
}

const patch = (model, fileId, change) => ({
  ...model,
  rows: { ...model.rows, [fileId]: { ...model.rows[fileId], ...change } },
});

/** Puts a file in an edition (a fixed edition's key, or {@link NEW}); a new edition starts with no name. */
export const setEdition = (model, fileId, edition) =>
  patch(model, fileId, { edition, newName: '' });
/** Types a new edition's name (kept to 32 characters). */
export const setNewName = (model, fileId, text) =>
  patch(model, fileId, { newName: text.slice(0, 32) });
/** Sets a file's part: "single" or "1" to "8". */
export const setPart = (model, fileId, part) => patch(model, fileId, { part });
/** Marks a file Not this film, or moves it back. */
export const setNot = (model, fileId, not) => patch(model, fileId, { not });
/** Chooses the default edition by name. */
export const setDefault = (model, name) => ({ ...model, def: name });

const partNumber = (row) => (row.part === 'single' ? 0 : Number(row.part));

function groupsOf(model) {
  const byKey = new Map();
  model.files
    .filter((f) => !model.rows[f.id].not)
    .forEach((file) => {
      const row = model.rows[file.id];
      const fixed = row.edition === NEW ? null : model.fixed.find((x) => x.key === row.edition);
      const name = fixed ? fixed.name : row.newName.trim();
      const key = fixed ? fixed.key : `n:${name}`;
      if (!byKey.has(key)) {
        byKey.set(key, { key, id: fixed ? fixed.id : null, name, isNew: !fixed, entries: [] });
      }
      byKey.get(key).entries.push({ file, row });
    });
  const groups = [...byKey.values()];
  adoptHistory(model, groups);
  groups.forEach((g) => g.entries.sort((a, b) => partNumber(a.row) - partNumber(b.row)));
  return groups;
}

/**
 * A new edition that takes over the files of an existing one the owner has emptied is that edition renamed: it
 * keeps its id, so its resume position and watched state go with it. The existing edition holding most of its files
 * is chosen (the lower id on a tie), if no other group already uses it.
 */
function adoptHistory(model, groups) {
  const used = new Set(groups.map((g) => g.id).filter((id) => id != null));
  groups
    .filter((g) => g.id == null)
    .forEach((group) => {
      const counts = new Map();
      group.entries.forEach(({ file }) => {
        const id = model.current[file.id];
        if (!used.has(id)) counts.set(id, (counts.get(id) ?? 0) + 1);
      });
      let best = null;
      counts.forEach((count, id) => {
        if (best === null || count > best.count || (count === best.count && id < best.id)) {
          best = { id, count };
        }
      });
      if (best) {
        group.id = best.id;
        used.add(best.id);
      }
    });
}

/** The save request for the current arrangement. */
export function buildRequest(model) {
  const groups = groupsOf(model);
  return {
    editions: groups.map((g) => ({
      id: g.id,
      name: g.name,
      default: g.name !== '' && g.name === model.def,
      files: g.entries.map(({ file, row }) => ({
        fileId: file.id,
        part: row.part === 'single' ? null : Number(row.part),
      })),
    })),
    notThisFilm: model.files.filter((f) => model.rows[f.id].not).map((f) => f.id),
  };
}

function summary(entries) {
  const total = entries.reduce((sum, { file }) => sum + (file.durationMs ?? 0), 0);
  let parts = `${entries.length} parts`;
  if (entries.length === 1) parts = entries[0].row.part === 'single' ? 'single file' : '1 part';
  return `${parts} · ${formatRuntime(total)}`;
}

/**
 * Everything the panel draws: the groups with their rows and errors, the Not this film rows, the default radios and
 * the error count. `serverErrors` are the server's answer to a rejected save, kept until the next edit.
 */
export function describeModel(model, serverErrors = []) {
  const groups = groupsOf(model);
  const request = buildRequest(model);
  const client = validateArrangement(
    request,
    model.files.map((f) => f.id),
  );
  const errors = [
    ...client,
    ...serverErrors.filter(
      (s) => !client.some((c) => c.field === s.field && c.message === s.message),
    ),
  ];
  const messageFor = (field) => errors.find((e) => e.field === field)?.message ?? '';
  const described = groups.map((g, i) => {
    const nameError = messageFor(`editions[${i}].name`);
    const partsError = messageFor(`editions[${i}].parts`);
    const shown = g.name === '' ? UNNAMED : g.name;
    return {
      // Stable while the owner types a new name (the group is found by its name, but must not remount).
      key: g.isNew ? `new-${g.entries[0].file.id}` : g.key,
      name: shown,
      label: `Edition ${shown}`,
      summary: summary(g.entries),
      // A name problem on a group that has no new-name field to carry it is said under the heading.
      error: partsError || (g.isNew ? '' : nameError),
      partsInError: Boolean(partsError),
      rows: g.entries.map(({ file, row }) => ({
        file,
        row,
        isNew: row.edition === NEW,
        nameError: row.edition === NEW ? nameError : '',
      })),
    };
  });
  return {
    groups: described,
    notRows: model.files.filter((f) => model.rows[f.id].not),
    defaults: groups.map((g, i) => ({
      name: described[i].name,
      meta: described[i].summary,
      on: model.def === g.name,
    })),
    noEditions: messageFor('editions'),
    noDefault: messageFor('default'),
    other: errors.filter((e) => e.field === 'files').map((e) => e.message),
    errorCount: errors.length,
    request,
  };
}

/** The footer note: loading, how many problems, or the reassurance. */
export function footNote(loading, errorCount) {
  if (loading) return 'Loading files…';
  if (errorCount > 0)
    return `Fix ${errorCount} ${errorCount === 1 ? 'problem' : 'problems'} to save.`;
  return 'Resume positions and watched state are kept for each edition.';
}

/** What the save-failed banner says (after "Couldn’t save."), and the server's field errors when it sent some. */
export function saveProblem(failure) {
  const status = failure instanceof ApiError ? failure.status : 0;
  if (status === 409) {
    return {
      text: 'A library scan is running — try again when it finishes. Your changes are still here.',
      fieldErrors: [],
    };
  }
  const fieldErrors = failure?.body?.errors;
  if (status === 400 && Array.isArray(fieldErrors) && fieldErrors.length > 0) {
    return { text: `${fieldErrors[0].message} Your changes are still here.`, fieldErrors };
  }
  return { text: 'Something went wrong — try again.', fieldErrors: [] };
}
