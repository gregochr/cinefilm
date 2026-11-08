/** Static skeleton blocks (no shimmer) at the real layout's sizes. */
export function SkeletonBlock({ className = '', style }) {
  return (
    <div
      className={`skeleton ${className}`.trim()}
      style={style}
      aria-hidden="true"
      data-testid="skeleton"
    />
  );
}

export function SkeletonRow({ count = 7, variant = 'posters' }) {
  return (
    <div
      className={`row__grid row__grid--${variant}`}
      aria-hidden="true"
      data-testid="skeleton-row"
    >
      {Array.from({ length: count }, (_, i) => (
        <SkeletonBlock
          key={i}
          className={variant === 'stills' ? 'skeleton--still' : 'skeleton--poster'}
        />
      ))}
    </div>
  );
}

export function HomeSkeleton() {
  return (
    <div className="page__stack" role="status" aria-label="Loading">
      <SkeletonBlock className="skeleton--hero" />
      <SkeletonRow count={4} variant="stills" />
      <SkeletonRow />
      <SkeletonRow />
    </div>
  );
}

export function GridSkeleton({ count = 18 }) {
  return (
    <div className="poster-grid" role="status" aria-label="Loading">
      {Array.from({ length: count }, (_, i) => (
        <SkeletonBlock key={i} className="skeleton--poster" />
      ))}
    </div>
  );
}

export function DetailSkeleton() {
  return (
    <div className="detail-skeleton" role="status" aria-label="Loading">
      <SkeletonBlock className="skeleton--poster detail-skeleton__poster" />
      <div className="detail-skeleton__text">
        <SkeletonBlock style={{ height: 96, maxWidth: 420 }} />
        <SkeletonBlock style={{ height: 20, maxWidth: 320 }} />
        <SkeletonBlock style={{ height: 80, maxWidth: 640 }} />
        <SkeletonBlock style={{ height: 52, maxWidth: 360 }} />
      </div>
    </div>
  );
}
