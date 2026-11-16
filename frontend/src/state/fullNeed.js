// Display and rule helpers for Admin → Subtitles → "Full subtitles". No JSX: the Apple TV and native clients reuse
// them. The server decides each film's need; this only words what it sent.

const REASON_WORDS = {
  FOREIGN_AUDIO: 'Audio is in another language',
  ENGLISH_AUDIO: 'Audio is English',
  ORIGINAL_AUDIO_ASSUMED: 'Audio not labelled: original language assumed',
  ENGLISH_FILM: 'English-language film',
  OWNER: 'Set by you',
  NO_LANGUAGES: 'Waiting for TMDB',
};

/** The reason for a film's need in plain words; empty for a reason this screen does not know. */
export function fullNeedReason(row) {
  return REASON_WORDS[row.reason] ?? '';
}

/**
 * Where a film's full English subtitles come from, in one line: the owner's file, a download that plays unchecked, or
 * none yet. The server says which plays (`fullSubtitle.source`); nothing is derived here.
 */
export function fullSubtitleLine(row) {
  const source = row.fullSubtitle?.source;
  if (source === 'SIDECAR') return 'Playing: your file';
  if (source === 'DOWNLOAD') return 'Playing: download (not checked yet)';
  return 'None yet';
}
