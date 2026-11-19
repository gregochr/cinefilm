import {
  keywordLine,
  keywordPercent,
  keywordsPending,
  meterCaption,
  meterSegments,
  statusText,
} from '../../state/christmas.js';

/** The status card: how many films Home will show, the 12-slot meter, and (while keywords arrive) the progress strip. */
export default function ChristmasStatusCard({ data }) {
  const { max } = data.limits;
  const total = data.inWeek.length;
  const text = statusText(data);
  const segments = meterSegments(total, max);
  const caption = meterCaption(total, max);
  return (
    <section className="xmas-card" aria-label="Christmas week status">
      <div className="xmas-card__top">
        <div>
          <p className="xmas-card__headline">
            <strong>{text.bold}</strong>
            {text.rest}
          </p>
          <p className="xmas-card__hint">{text.hint}</p>
        </div>
        {/* Decorative: the sentence carries the meaning. */}
        <div
          className="xmas-meter"
          aria-hidden="true"
          data-testid="meter"
          data-filled={Math.min(total, max)}
          data-over={Math.max(0, total - max)}
        >
          <div className="xmas-meter__bar">
            {segments.map((kind, i) => (
              <span key={i} className={`xmas-meter__seg xmas-meter__seg--${kind}`} />
            ))}
          </div>
          <p className="xmas-meter__caption">
            <span>{caption.places}</span>
            {caption.over ? <span>{caption.over}</span> : null}
          </p>
        </div>
      </div>
      {keywordsPending(data.keywords) ? (
        <div className="xmas-card__keywords">
          <span className="xmas-progress" aria-hidden="true">
            <span
              className="xmas-progress__fill"
              data-testid="keyword-fill"
              style={{ width: `${keywordPercent(data.keywords)}%` }}
            />
          </span>
          <p>{keywordLine(data.keywords)}</p>
        </div>
      ) : null}
    </section>
  );
}
