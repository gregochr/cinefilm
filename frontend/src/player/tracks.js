// Audio, subtitle and quality option helpers shared by the detail page and the player.
import { resolutionLabel } from '../state/resolution.js';

export const SUBTITLE_OFF = 'off';

/** The key of the forced-subtitle track: its own key, not a stream's, since it is not a menu choice. */
export const FORCED_KEY = 'forced';

/**
 * The key and saved value of the edition's full English subtitle file (a menu choice labelled "English (full)", like
 * "off" a literal rather than a stream id).
 */
export const FULL_KEY = 'full';

/** Streams are identified by `id` when the API sends one, else by `index`. */
export const streamKey = (stream) => stream.id ?? stream.index;

export function audioLabel(stream) {
  const name = stream.title || [stream.language, stream.channels].filter(Boolean).join(' ');
  return { name, detail: stream.codec || '' };
}

export function audioText(stream) {
  const { name, detail } = audioLabel(stream);
  return detail ? `${name} · ${detail}` : name;
}

export function subtitleLabel(stream) {
  const base = stream.title || stream.language || 'Subtitles';
  return stream.forced ? `${base} (forced)` : base;
}

/**
 * Off first, then "English (full)" when the edition has a full subtitle file (`fullSubtitle`), then each stream.
 * `value` is SUBTITLE_OFF, FULL_KEY or the stream key.
 */
export function subtitleOptions(streams = [], fullSubtitle = null) {
  return [
    { value: SUBTITLE_OFF, label: 'Off', note: 'forced if needed' },
    ...(fullSubtitle ? [{ value: FULL_KEY, label: 'English (full)', note: '' }] : []),
    ...streams.map((s) => ({ value: streamKey(s), label: subtitleLabel(s), note: '' })),
  ];
}

/** C key: Off → English (full) → English → English (forced) → Off. No subtitles: stays Off. */
export function nextSubtitle(streams = [], current = SUBTITLE_OFF, fullSubtitle = null) {
  const values = subtitleOptions(streams, fullSubtitle).map((o) => o.value);
  if (values.length < 2) return SUBTITLE_OFF;
  const at = values.findIndex((v) => v === current);
  return values[(at + 1) % values.length];
}

const sameText = (a, b) => (a ?? '').toLowerCase() === (b ?? '').toLowerCase();
const hasKey = (stream, key) => String(streamKey(stream)) === String(key);
const owner = (editions, kind, key) => editions.find((e) => e[kind].some((s) => hasKey(s, key)));

/**
 * The saved audio choice is the id of a stream in one edition's file, but a film's editions are separate files
 * with separate ids. For the selected edition: the saved stream itself if it belongs to it, else its equivalent
 * (same language and title, then same language and channels), else null (the edition's default plays).
 */
export function resolveAudioChoice(editions, edition, saved) {
  const own = edition.audioStreams.find((s) => hasKey(s, saved));
  if (own) return streamKey(own);
  const home = owner(editions, 'audioStreams', saved);
  if (!home) return null;
  const source = home.audioStreams.find((s) => hasKey(s, saved));
  const sameLanguage = edition.audioStreams.filter((s) => sameText(s.language, source.language));
  const match =
    sameLanguage.find((s) => sameText(s.title, source.title)) ||
    sameLanguage.find((s) => sameText(s.channels, source.channels));
  return match ? streamKey(match) : null;
}

/** Subtitle formats the player can show as text; mirrors the backend's text codecs (`FfprobeParser.TEXT_SUBTITLES`). */
export const TEXT_SUBTITLE_FORMATS = [
  'mov_text',
  'tx3g',
  'subrip',
  'srt',
  'ass',
  'ssa',
  'webvtt',
  'text',
];

// A missing format is accepted: every stored stream is text.
const isTextFormat = (stream) =>
  !stream.format || TEXT_SUBTITLE_FORMATS.includes(String(stream.format).toLowerCase());

const isEnglishText = (stream) =>
  /^english$/i.test(stream.language ?? '') && !stream.forced && isTextFormat(stream);

/**
 * The saved subtitle choice ("off", "full" or a stream id) for the selected edition. Nothing saved stays null
 * ("never chosen", decision 68). A saved "full" is "full" when this edition has a full file, else an English text
 * stream if it has one, else Off. A stream id is the same stream if it belongs to the edition, else the stream with
 * the same language and forced flag, else Off.
 */
export function resolveSubtitleChoice(editions, edition, saved) {
  if (saved === undefined || saved === null) return null;
  if (saved === SUBTITLE_OFF) return SUBTITLE_OFF;
  if (saved === FULL_KEY) {
    if (edition.fullSubtitle) return FULL_KEY;
    const english = edition.subtitleStreams.find(isEnglishText);
    return english ? streamKey(english) : SUBTITLE_OFF;
  }
  const own = edition.subtitleStreams.find((s) => hasKey(s, saved));
  if (own) return streamKey(own);
  const home = owner(editions, 'subtitleStreams', saved);
  if (!home) return SUBTITLE_OFF;
  const source = home.subtitleStreams.find((s) => hasKey(s, saved));
  const match = edition.subtitleStreams.find(
    (s) => sameText(s.language, source.language) && Boolean(s.forced) === Boolean(source.forced),
  );
  return match ? streamKey(match) : SUBTITLE_OFF;
}

const isEnglishOrUnlabelled = (stream) => !stream.language || /^english$/i.test(stream.language);

/**
 * The subtitle track key that should be showing. A chosen stream shows (and hides the forced track). When
 * Subtitles is Off the forced English lines show by themselves, because they translate the foreign-language
 * lines a film expects you not to understand (decision 6): the edition's `forcedSubtitle` (the API already chose
 * the winner, so it beats an embedded forced stream) as `FORCED_KEY`, else an embedded forced English stream
 * (language unknown counts). Otherwise none.
 */
export function activeSubtitleKey(
  streams = [],
  choice = SUBTITLE_OFF,
  forcedSubtitle = null,
  fullSubtitle = null,
) {
  if (choice === FULL_KEY && fullSubtitle) return FULL_KEY;
  if (choice !== SUBTITLE_OFF && choice !== FULL_KEY && choice !== null) {
    const chosen = streams.find((s) => String(streamKey(s)) === String(choice));
    return chosen ? streamKey(chosen) : null;
  }
  if (forcedSubtitle) return FORCED_KEY;
  const forced = streams.find((s) => s.forced && isEnglishOrUnlabelled(s));
  return forced ? streamKey(forced) : null;
}

/** True when the edition has anything to show: subtitle streams, or a forced or full subtitle file of its own. */
export function hasSubtitleTracks(edition) {
  return (
    edition.subtitleStreams.length > 0 ||
    Boolean(edition.forcedSubtitle) ||
    Boolean(edition.fullSubtitle)
  );
}

/** Whether an audio track's language label says English ("english", "eng" or "en", any case). Unlabelled is not. */
export function isEnglishLabel(language) {
  return /^(english|eng|en)$/i.test((language ?? '').trim());
}

/**
 * The subtitle choice in effect. A choice the viewer made (Off included) is kept. When nothing was ever chosen
 * (`choice` null), the film needs full subtitles (`fullNeed` NEEDED), the edition has a full file and the selected
 * audio is not labelled English, the full subtitle is on; otherwise Off. The default is recomputed, never saved, so
 * switching the audio to English turns it off again while nothing has been chosen.
 */
export function effectiveSubtitleChoice({ choice, fullNeed, fullSubtitle, audioLanguage }) {
  if (choice !== null && choice !== undefined) return choice;
  if (fullNeed === 'NEEDED' && fullSubtitle && !isEnglishLabel(audioLanguage)) return FULL_KEY;
  return SUBTITLE_OFF;
}

export function formatBitrate(bps) {
  if (!bps) return '';
  const mbps = bps / 1_000_000;
  return `${Number.isInteger(mbps) ? mbps : mbps.toFixed(1)} Mbps`;
}

export function qualityName(id) {
  if (id === 'auto') return 'Auto';
  if (id === 'original') return 'Original';
  return id;
}

/** The mono detail shown on the right of a quality row. */
export function qualityDetail(quality, { width, activeRung = 'original' } = {}) {
  if (quality.id === 'auto') return `now ${qualityName(activeRung)}`;
  const bitrate = formatBitrate(quality.bitrate);
  const parts = [];
  const resolution = quality.id === 'original' ? resolutionLabel(width) : null;
  if (resolution) parts.push(resolution);
  if (bitrate) parts.push(bitrate);
  if (quality.premade) parts.push('pre-made');
  return parts.join(' · ');
}

export function currentQualityLabel(quality = 'auto') {
  return qualityName(quality);
}
