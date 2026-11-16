import { useCallback, useRef, useState } from 'react';
import { adminApi, filmsApi } from '../api/resources.js';
import { attachTarget, fileChoices, isCurrentTarget } from './adminSubtitles.js';
import { subtitleProblem } from './subtitleAdmin.js';
import { useResource } from './useResource.js';

/** The one cache key of the library's subtitle file list that every "Add my own file…" form reads. */
export const ADD_FILE_FILES_KEY = 'admin-add-file-files';
/** How long a form may reuse the list another form read. */
export const ADD_FILE_FILES_MAX_AGE_MS = 60_000;

/**
 * "Add my own file…" for one film: the owner's own full subtitle files that could belong to it (`files`; with
 * `allKinds`, forced ones too and this film's own, see `fileChoices`), the film's editions (`GET /api/films/{id}`),
 * and the choice of a file, an edition (picked already for a film with one) and, for
 * a split edition, the whole film or one part. `attach()` posts the existing attach route for the chosen file and
 * resolves true once the server accepted it; a second call while one is in flight does nothing (a ref, so two clicks
 * in one frame cannot both pass), and a failure sets `problem` in words. Nothing is sent until `attach()`.
 */
export function useFilmFilePicker(filmId, { allKinds = false } = {}) {
  // The whole-library file list is the same for every film, so every open form shares one cached read (one key, not
  // one per film); the cache is emptied by any change made in this tab, an attach included, so the next form reads
  // the list again.
  const filesRead = useResource(ADD_FILE_FILES_KEY, (options) => adminApi.subtitles(options), {
    cache: 'fresh',
    maxAgeMs: ADD_FILE_FILES_MAX_AGE_MS,
  });
  const filmRead = useResource(`admin-add-file-film:${filmId}`, (options) =>
    filmsApi.get(filmId, options),
  );
  const [fileId, setFileId] = useState(null);
  const [editionId, setEditionId] = useState(null);
  const [part, setPart] = useState(null);
  const [attaching, setAttaching] = useState(false);
  const [problem, setProblem] = useState(null);
  const running = useRef(false);

  const files = filesRead.data ? fileChoices(filesRead.data, filmId, allKinds) : null;
  const editions = filmRead.data?.id === filmId ? filmRead.data.editions : [];
  const edition =
    editions.find((e) => e.id === editionId) ?? (editions.length === 1 ? editions[0] : null);
  const target = fileId != null ? attachTarget(edition, part) : null;
  // Attaching a file to where it already is changes nothing, but the server clears its check: not offered.
  const unchanged =
    target !== null &&
    isCurrentTarget(
      files?.find((file) => file.id === fileId),
      filmId,
      target,
    );

  const attach = useCallback(async () => {
    if (running.current || target === null || unchanged) return false;
    running.current = true;
    setAttaching(true);
    setProblem(null);
    try {
      await adminApi.attachSubtitle(fileId, target);
      return true;
    } catch (failure) {
      setProblem(subtitleProblem(failure));
      return false;
    } finally {
      running.current = false;
      setAttaching(false);
    }
  }, [fileId, target, unchanged]);

  return {
    files,
    filesError: filesRead.error != null && !filesRead.data,
    filesLoading: !filesRead.data && filesRead.error == null,
    retryFiles: filesRead.reload,
    fileId,
    pickFile: setFileId,
    editions,
    editionsError: filmRead.error != null,
    editionsLoading: filmRead.data?.id !== filmId && filmRead.error == null,
    retryEditions: filmRead.reload,
    edition,
    pickEdition: (id) => {
      setEditionId(id);
      setPart(null);
    },
    part,
    pickPart: setPart,
    target,
    unchanged,
    attaching,
    problem,
    attach,
  };
}
