import { BACKDROP_SIZE, POSTER_SIZE, imageUrl } from '../api/images.js';
import { placeholderColor } from './placeholder.js';

/**
 * Poster (2:3), still (16:9) or backdrop artwork. Reserves its aspect ratio, lazy-loads, and
 * falls back to a stable warm colour block with the title in serif.
 */
export default function Art({
  kind = 'poster',
  path,
  id,
  title,
  size,
  showTitle = true,
  className = '',
  children,
}) {
  const defaultSize = kind === 'poster' ? POSTER_SIZE : BACKDROP_SIZE;
  const url = imageUrl(path, size || defaultSize);
  const classes = `art art--${kind} ${className}`.trim();
  if (url) {
    return (
      <div className={classes} data-testid="art">
        <img className="art__img" src={url} alt="" loading="lazy" decoding="async" />
        {children}
      </div>
    );
  }
  return (
    <div
      className={`${classes} art--placeholder`}
      data-testid="art"
      style={{ background: placeholderColor(id, title) }}
    >
      {showTitle && kind === 'poster' ? <span className="art__title">{title}</span> : null}
      {children}
    </div>
  );
}
