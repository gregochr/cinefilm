// Audio, subtitle and quality option helpers shared by the detail page and the player.

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
export function qualityDetail(quality, { height } = {}) {
  if (quality.id === 'auto') return 'now Original';
  const bitrate = formatBitrate(quality.bitrate);
  const parts = [];
  if (quality.id === 'original' && height) parts.push(`${height}p`);
  if (bitrate) parts.push(bitrate);
  if (quality.premade) parts.push('pre-made');
  return parts.join(' · ');
}

/** The corner indicator. Phase 1 always plays the original at home. */
export function indicatorText(quality = 'auto') {
  return quality === 'original' ? 'Original · Home' : 'Auto · Original · Home';
}

export function currentQualityLabel(quality = 'auto') {
  return qualityName(quality);
}
