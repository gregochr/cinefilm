import { Link } from 'react-router-dom';

/**
 * A Home row: serif heading (focusable by script only, `headingRef`), muted sub-line, optional "See all"
 * (named `seeAllLabel`, or "See all: {title}"), and a grid / phone scroller.
 */
export default function FilmRow({
  title,
  sub,
  seeAllTo,
  seeAllLabel,
  headingRef,
  variant = 'posters',
  children,
}) {
  const headingId = `row-${title.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`;
  return (
    <section className="row" aria-labelledby={headingId}>
      <div className="row__head">
        <h2 id={headingId} className="row__title" tabIndex={-1} ref={headingRef}>
          {title}
        </h2>
        {sub ? <span className="row__sub">{sub}</span> : null}
        <span className="row__spacer" />
        {seeAllTo ? (
          <Link to={seeAllTo} className="row__all" aria-label={seeAllLabel ?? `See all: ${title}`}>
            See all
          </Link>
        ) : null}
      </div>
      <div className={`row__grid row__grid--${variant}`}>{children}</div>
    </section>
  );
}
