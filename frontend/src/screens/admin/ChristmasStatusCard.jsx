import {
  KEYWORDS_NOT_FETCHING_TEXT,
  keywordLine,
  keywordsStalled,
  keywordPercent,
  keywordsPending,
  meterCaption,
  meterSegments,
  statusText,
} from '../../state/christmas.js';
import ChristmasPreview from './ChristmasPreview.jsx';

/**
 * The status card: how many films Home will show, the 12-slot meter, (while keywords arrive) the progress strip, and
 * the Home preview. The phone props belong to the screen, which keeps the preview sheet's state beside the search
 * sheet's so the toast follows whichever is up.
 */
export default function ChristmasStatusCard({
  data,
  phone,
  previewSheetOpen,
  onPreviewSheetOpenChange,
  toast,
}) {
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
          {/* Polite: the 30-second polls change this sentence only when more keywords have arrived. */}
          <p role="status">
            {keywordsStalled(data.keywords)
              ? KEYWORDS_NOT_FETCHING_TEXT
              : keywordLine(data.keywords)}
          </p>
        </div>
      ) : null}
      <ChristmasPreview
        data={data}
        phone={phone}
        sheetOpen={previewSheetOpen}
        onSheetOpenChange={onPreviewSheetOpenChange}
        toast={toast}
      />
    </section>
  );
}
