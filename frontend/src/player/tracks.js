// Audio, subtitle and quality option helpers shared by the detail page and the player.
import { resolutionLabel } from '../state/resolution.js';

export const SUBTITLE_OFF = 'off';

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

/** Off first, then each stream. `value` is SUBTITLE_OFF or the stream key. */
export function subtitleOptions(streams = []) {
  return [
    { value: SUBTITLE_OFF, label: 'Off', note: 'forced if needed' },
    ...streams.map((s) => ({ value: streamKey(s), label: subtitleLabel(s), note: '' })),
  ];
}

/** C key: Off → English → English (forced) → Off. No subtitles: stays Off. */
export function nextSubtitle(streams = [], current = SUBTITLE_OFF) {
  const values = subtitleOptions(streams).map((o) => o.value);
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

/**
 * The saved subtitle choice ("off" or a stream id) for the selected edition: the same stream if it belongs to
 * it, else the stream with the same language and forced flag, else Off.
 */
export function resolveSubtitleChoice(editions, edition, saved) {
  if (saved === undefined || saved === null || saved === SUBTITLE_OFF) return SUBTITLE_OFF;
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
 * The subtitle stream key that should be showing. A chosen stream shows. When Subtitles is Off the
 * forced English track (language unknown counts) shows by itself, because forced tracks translate
 * the foreign-language lines a film expects you not to understand (decision 6). Otherwise none.
 */
export function activeSubtitleKey(streams = [], choice = SUBTITLE_OFF) {
  if (choice !== SUBTITLE_OFF) {
    const chosen = streams.find((s) => String(streamKey(s)) === String(choice));
    return chosen ? streamKey(chosen) : null;
  }
  const forced = streams.find((s) => s.forced && isEnglishOrUnlabelled(s));
  return forced ? streamKey(forced) : null;
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
