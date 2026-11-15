// Display helpers for the Admin transcoding panel. No JSX: the Apple TV and native clients reuse them.

const ENCODERS = {
  vaapi: 'VAAPI (hardware)',
  software: 'Software (x264)',
  unavailable: 'Unavailable',
};

/** The encoder's name; an unknown value shows as is. */
export const encoderLabel = (encoder) => ENCODERS[encoder] ?? encoder;

/**
 * "ready" with the encoder's name; "Unavailable" alone when the server found no encoder. The server reports no
 * further status, so "ready" means an encoder was found.
 */
export const encoderStatus = (encoder) =>
  encoder === 'unavailable' ? encoderLabel(encoder) : `${encoderLabel(encoder)} · ready`;

/** "1,409 of 1,412 films": how many films have their keyframe (seek) index. */
export const seekIndexText = ({ indexed, total }) =>
  `${indexed.toLocaleString('en-GB')} of ${total.toLocaleString('en-GB')} films`;

const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];

/** "0 B", "1.5 KB", "2.3 GB": 1024-based, one decimal from KB up. */
export function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
  const exponent = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), UNITS.length - 1);
  if (exponent === 0) return `${Math.round(bytes)} B`;
  return `${(bytes / 1024 ** exponent).toFixed(1)} ${UNITS[exponent]}`;
}
