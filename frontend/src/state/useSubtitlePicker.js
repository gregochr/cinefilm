import { useCallback, useEffect, useRef, useState } from 'react';
import { adminApi } from '../api/resources.js';
import { pickerProblem } from './subtitlePicker.js';
import { useResource } from './useResource.js';

/**
 * The state behind the OpenSubtitles picker for one edition and `kind` (`full` or `forced`).
 *
 * The search is read on mount, in the server's ranking (nothing is sorted or filtered here). `status` is `loading`,
 * `error` (`searchProblem` is the server's words for it; `retry` searches again) or `ready` (`results`,
 * `downloadsLeft`). A search that is replaced by a newer one (`retry`, or another edition or kind) is aborted and its
 * late answer is ignored (`useResource`).
 *
 * `choose(result)` downloads one file. It is one request at a time: a second call while a download is running, or
 * after one succeeded, does nothing (a ref, so two clicks in one frame cannot both pass). `downloading` is the file id
 * being fetched. A failure frees the picker for another choice and sets `problem` to the server's own words. On
 * success `onDownloaded(answer, { open })` runs once; `open` is false when the picker was closed before the answer
 * came (the download is not aborted, the server may still have saved it): the caller hears about it either way, so
 * what depends on the download is read again. A failure after the picker was closed has nobody to show it to.
 */
export function useSubtitlePicker({ editionId, kind, onDownloaded }) {
  const search = useResource(`subtitle-picker:${editionId}:${kind}`, (options) =>
    adminApi.searchOpenSubtitles(editionId, kind, options),
  );
  const [download, setDownload] = useState({ fileId: null, problem: null });
  const busy = useRef(false);
  const open = useRef(true);
  // `onDownloaded` is read when the answer arrives, so the latest one runs without making `choose` change.
  const downloaded = useRef(onDownloaded);
  downloaded.current = onDownloaded;

  useEffect(() => {
    open.current = true;
    return () => {
      open.current = false;
    };
  }, []);

  const choose = useCallback(
    async (result) => {
      if (busy.current) return;
      busy.current = true;
      setDownload({ fileId: result.fileId, problem: null });
      let answer;
      try {
        answer = await adminApi.downloadOpenSubtitles(editionId, { fileId: result.fileId, kind });
      } catch (failure) {
        busy.current = false;
        setDownload({ fileId: null, problem: pickerProblem(failure, 'download') });
        return;
      }
      // Success keeps the picker busy: whatever closes it must not be followed by a second download.
      downloaded.current(answer, { open: open.current });
    },
    [editionId, kind],
  );

  let status = 'ready';
  if (search.loading) status = 'loading';
  else if (search.error) status = 'error';
  return {
    status,
    results: status === 'ready' ? search.data.results : [],
    downloadsLeft: status === 'ready' ? search.data.downloadsLeft : null,
    searchProblem: status === 'error' ? pickerProblem(search.error, 'search') : null,
    retry: search.reload,
    downloading: download.fileId,
    problem: download.problem,
    choose,
  };
}
