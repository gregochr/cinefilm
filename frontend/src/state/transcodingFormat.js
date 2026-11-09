// Display helpers for the Admin transcoding panel. No JSX: the Apple TV and native clients reuse them.

const ENCODERS = {
  vaapi: 'VAAPI (hardware)',
  software: 'Software (x264)',
  unavailable: 'Unavailable',
};

/** The encoder's name; an unknown value shows as is. */
export const encoderLabel = (encoder) => ENCODERS[encoder] ?? encoder;

/** "12 of 40 films indexed". */
export const indexText = ({ indexed, total }) => `${indexed} of ${total} films indexed`;

const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];

/** "0 B", "1.5 KB", "2.3 GB": 1024-based, one decimal from KB up. */
export function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
  const exponent = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), UNITS.length - 1);
  if (exponent === 0) return `${Math.round(bytes)} B`;
  return `${(bytes / 1024 ** exponent).toFixed(1)} ${UNITS[exponent]}`;
}

/** The encoder's speed as "2.4×" (times real time), or "—" before it has reported one. */
export const formatSpeed = (speed) =>
  typeof speed === 'number' && Number.isFinite(speed) ? `${speed.toFixed(1)}×` : '—';

/** "3 segments ahead", "1 segment ahead". */
export const aheadText = (segments) =>
  `${segments} ${segments === 1 ? 'segment' : 'segments'} ahead`;
