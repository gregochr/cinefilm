// What the OpenSubtitles picker shows for each result and how it words a failure. Pure, no JSX: the Apple TV and
// native clients reuse it. The server ranks the results (AGENTS.md rule 3): nothing here sorts, filters or scores.
import { ApiError } from '../api/client.js';
import { subtitlePicker } from './adminCopy.js';

/** The name a result is shown under: its release name, else its file name, else its file id. */
export function resultName(result) {
  return result.release || result.fileName || subtitlePicker.unnamed(result.fileId);
}

/** The year at the start of an upload date as OpenSubtitles wrote it ("2019-05-14T09:12:00Z"), or null. */
function uploadYear(uploadDate) {
  const match = /^(\d{4})-/.exec(uploadDate ?? '');
  return match ? match[1] : null;
}

/**
 * The facts a result shows under its name, in a fixed order, each only when the server sent it: downloads, frame
 * rate, "Trusted uploader", "Hearing impaired" and the year it was uploaded.
 */
export function resultDetails(result) {
  const { detail } = subtitlePicker;
  const year = uploadYear(result.uploadDate);
  return [
    detail.downloads(result.downloadCount),
    result.fps == null ? null : detail.fps(result.fps),
    result.fromTrusted ? detail.trusted : null,
    result.hearingImpaired ? detail.hearingImpaired : null,
    year ? detail.uploaded(year) : null,
  ].filter(Boolean);
}

/**
 * The line a search or a download that failed shows: the server's own `error` text when it gave one (every refusal
 * does: 400, 409, 422, 429, 502, 503), else the daily-limit words for a 429 and a plain line for anything else
 * (a network failure, a body with no text).
 */
export function pickerProblem(failure, phase) {
  const text = failure instanceof ApiError ? failure.body?.error : null;
  if (typeof text === 'string' && text.trim()) return text;
  if (failure instanceof ApiError && failure.status === 429) return subtitlePicker.quota;
  return phase === 'search' ? subtitlePicker.searchError : subtitlePicker.downloadError;
}
