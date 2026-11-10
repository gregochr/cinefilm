// The "file line" under an edition on Film detail. No JSX, no DOM.
import { formatRuntime } from '../player/time.js';
import { resolutionLabel } from './resolution.js';

function addedLabel(iso) {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return `added ${date.toLocaleString('en-GB', { month: 'long', year: 'numeric' })}`;
}

/**
 * "Original file · 1080p H.264 · 5.1 · added March 2024" for a film in one piece, with what is unknown left out.
 * An edition split across several files reads as the one edition it is, and the only trace of the split is how many
 * files make it up: "Extended Edition · 2 parts · 3h 55m · 1080p HEVC". Parts are never numbered or named.
 */
export function fileLine(edition) {
  const file = edition.file || {};
  const picture = [resolutionLabel(file.width), file.videoCodec].filter(Boolean).join(' ');
  const parts = edition.parts ?? 1;
  const items =
    parts > 1
      ? [edition.name, `${parts} parts`, formatRuntime(edition.runtimeMs), picture]
      : ['Original file', picture, file.audioSummary, addedLabel(file.addedAt)];
  return items.filter(Boolean).join(' · ');
}
