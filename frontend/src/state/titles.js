// Title and year text. A film whose year is unknown (the API sends `year: null`) shows no year and no separator.

/** "Heat · 1995" for a caption, "Heat" when the year is unknown. */
export function captionTitle(title, year) {
  return year ? `${title} · ${year}` : title;
}

/** "Heat, 1995" for an accessible label, "Heat" when the year is unknown. */
export function labelTitle(title, year) {
  return year ? `${title}, ${year}` : title;
}

/** "1995 · 2h 50m": the parts that exist, joined with the middle dot. */
export function joinMeta(...parts) {
  return parts.filter(Boolean).join(' · ');
}
