import { bucketColour, minuteStrip, STALL_COLOUR, STRIP } from '../state/playbackChart.js';
import { playbackChart as copy } from '../state/adminCopy.js';

/**
 * The per-minute view of one record's `minutes`: stall dots on top at the minute they happened, the median speed as
 * a line, and under it a band of the rung played in each minute. A record without a readable row says so instead.
 */
export default function PlaybackMinuteStrip({ minutes, film }) {
  const strip = minuteStrip(minutes);
  if (!strip) return <p className="admin__text">{copy.noMinutes}</p>;
  return (
    <div className="pchart__strip">
      <svg
        viewBox={`0 0 ${STRIP.width} ${STRIP.height}`}
        width="100%"
        role="img"
        aria-label={copy.stripLabel(film)}
      >
        {strip.bands.map((band) => (
          <rect
            key={band.x}
            data-testid="strip-band"
            x={band.x}
            y={STRIP.bandY}
            width={band.width}
            height={STRIP.bandHeight}
            fill={bucketColour(band.bucket)}
          />
        ))}
        {strip.line ? (
          <path
            data-testid="strip-line"
            d={strip.line}
            fill="none"
            stroke="#EDE6DA"
            strokeWidth="1.5"
            strokeLinecap="round"
            strokeLinejoin="round"
            vectorEffect="non-scaling-stroke"
          />
        ) : null}
        {strip.stalls.map((dot) => (
          <circle
            data-testid="strip-stall"
            key={dot.minute}
            cx={dot.x}
            cy={STRIP.dotY}
            r={dot.size / 2}
            fill={STALL_COLOUR}
          />
        ))}
      </svg>
      <div className="pchart__strip-axis admin__mono" aria-hidden="true">
        <span>{copy.stripStart}</span>
        <span>{copy.stripAxis}</span>
        <span>{strip.durationLabel}</span>
      </div>
    </div>
  );
}
