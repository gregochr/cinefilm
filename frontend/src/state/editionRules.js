// The rules an Edit editions save must keep. No JSX: the Apple TV and native clients reuse them.
//
// The server runs the same rules (`EditionRules.java`) and is the authority. Both read one table of cases,
// backend/src/test/resources/shared/edition-validation-cases.json, so the wording and the order of the errors cannot
// drift apart. Errors are `{ field, message }`; fields are paths into the request: `files`, `editions`,
// `editions[i].name`, `editions[i].parts`, `editions[i].files` and `default`, in that order: `files`, then each edition
// in turn (its name, then its parts), then `editions`, then `default`.

export const NAME_MAX = 32;
export const PART_MAX = 8;

export const MESSAGES = {
  unplaced: 'Every file has to be in an edition or marked Not this film.',
  twice: 'Each file can only be in one edition, or marked Not this film.',
  emptyEdition: 'Every edition needs at least one file.',
  nameBlank: 'Give this edition a name.',
  nameLong: 'Keep the name to 32 characters.',
  partRange: 'Parts go from 1 to 8.',
  singleMany:
    'Single file only works when an edition has one file — give each file a part number.',
  partOne: 'Only one file — set it to Single file or Part 1.',
  noEditions: 'Keep at least one file with this film.',
  noDefault: 'Choose a default edition.',
};

const trimmed = (edition) => (edition.name ?? '').trim();

/** An existing edition keeps its name over a new one; between two of a kind the earlier one keeps it. */
function beats(editions, other, index) {
  const otherExisting = editions[other].id != null;
  const existing = editions[index].id != null;
  return otherExisting !== existing ? otherExisting : other < index;
}

function nameError(editions, index) {
  const name = trimmed(editions[index]);
  if (name === '') return MESSAGES.nameBlank;
  if (name.length > NAME_MAX) return MESSAGES.nameLong;
  for (let j = 0; j < editions.length; j += 1) {
    if (j !== index && beats(editions, j, index)) {
      if (trimmed(editions[j]).toLowerCase() === name.toLowerCase()) {
        return `Another edition is already called ${trimmed(editions[j])}.`;
      }
    }
  }
  return null;
}

function partsMessage(files) {
  if (files.some((f) => f.part != null && (f.part < 1 || f.part > PART_MAX))) {
    return MESSAGES.partRange;
  }
  if (files.length === 1) {
    const { part } = files[0];
    return part != null && part !== 1 ? MESSAGES.partOne : null;
  }
  if (files.some((f) => f.part == null)) return MESSAGES.singleMany;
  const sorted = files.map((f) => f.part).sort((a, b) => a - b);
  for (let i = 1; i < sorted.length; i += 1) {
    if (sorted[i] === sorted[i - 1]) return `Two files are both part ${sorted[i]}.`;
  }
  const missing = [];
  for (let n = 1; n <= sorted[sorted.length - 1]; n += 1) {
    if (!sorted.includes(n)) missing.push(n);
  }
  if (missing.length === 0) return null;
  return `Parts ${sorted.join(' and ')} — part ${missing.join(' and ')} ${missing.length > 1 ? 'are' : 'is'} missing.`;
}

/**
 * Checks a save request against the film's files.
 * @param request `{ editions: [{ id, name, default, files: [{ fileId, part }] }], notThisFilm: [fileId] }`
 * @param fileIds every original file id of the film
 * @returns the broken rules as `{ field, message }`, in the fixed order; empty when the save is fine
 */
export function validateArrangement(request, fileIds) {
  const editions = request.editions ?? [];
  const notThis = request.notThisFilm ?? [];
  const errors = [];

  const assigned = [...notThis];
  editions.forEach((e) => (e.files ?? []).forEach((f) => assigned.push(f.fileId)));
  const distinct = new Set(assigned);
  const wanted = new Set(fileIds);
  if (distinct.size !== wanted.size || [...distinct].some((id) => !wanted.has(id))) {
    errors.push({ field: 'files', message: MESSAGES.unplaced });
  } else if (distinct.size !== assigned.length) {
    errors.push({ field: 'files', message: MESSAGES.twice });
  }

  editions.forEach((edition, i) => {
    const name = nameError(editions, i);
    if (name) errors.push({ field: `editions[${i}].name`, message: name });
    const files = edition.files ?? [];
    if (files.length === 0) {
      errors.push({ field: `editions[${i}].files`, message: MESSAGES.emptyEdition });
      return;
    }
    const parts = partsMessage(files);
    if (parts) errors.push({ field: `editions[${i}].parts`, message: parts });
  });

  if (editions.length === 0) {
    errors.push({ field: 'editions', message: MESSAGES.noEditions });
  } else if (editions.filter((e) => e.default).length !== 1) {
    errors.push({ field: 'default', message: MESSAGES.noDefault });
  }
  return errors;
}
