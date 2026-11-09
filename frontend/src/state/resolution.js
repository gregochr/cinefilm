// The display label for a source's resolution, classified by WIDTH so a letterboxed 1080p rip
// (1920x796) still reads "1080p". Unknown width gives no label. Rung ids ("720p"...) are encode
// behaviour and are not derived from this.
export function resolutionLabel(width) {
  if (!width || width <= 0) return null;
  if (width >= 3840) return '2160p (4K)';
  if (width >= 1920) return '1080p';
  if (width >= 1280) return '720p';
  return '480p';
}
