import { describeSubtitleSearch, formatScanTime } from '../../state/scanFormat.js';

/**
 * The OpenSubtitles search status ("Subtitles searched 12 Nov 06:00", "Searching for subtitles…"), moved here from the
 * Library scan card: it says when CineFilm last looked for subtitles, which belongs with the subtitle lists. `scan` is
 * the scan status; nothing is drawn before it has been read or when the search is off.
 */
export default function SubtitleSearchLine({ scan }) {
  if (!scan) return null;
  const text = describeSubtitleSearch(
    {
      enabled: Boolean(scan.openSubtitlesEnabled),
      running: Boolean(scan.openSubtitlesRunning),
      lastSearchedAt: scan.openSubtitlesLastSearchedAt,
    },
    formatScanTime,
  );
  if (!text) return null;
  return (
    <p className="admin__mono admin__ratings-line" role="status">
      {text}
    </p>
  );
}
