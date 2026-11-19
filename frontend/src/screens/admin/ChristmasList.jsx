import Art from '../../components/Art.jsx';
import { chipFor, cutIndex, cutText, rowNote, titleLine } from '../../state/christmas.js';

function ChristmasRow({ row, number, cut, tab }) {
  const chip = chipFor(row);
  const note = rowNote(row, tab);
  return (
    <li
      className={`xmas-row${cut ? ' xmas-row--cut' : ''}${number ? '' : ' xmas-row--plain'}`}
      aria-label={row.title}
    >
      {number ? (
        <span className="xmas-row__num" aria-hidden="true">
          {number}
        </span>
      ) : null}
      <Art
        kind="poster"
        path={row.posterUrl}
        id={row.filmId}
        title={row.title}
        showTitle={false}
        className="xmas-row__poster"
      />
      <div className="xmas-row__text">
        <p className="xmas-row__title">{titleLine(row)}</p>
        {note ? <p className="xmas-row__note">{note}</p> : null}
      </div>
      <span className={`xmas-chip xmas-chip--${chip.variant}`}>{chip.label}</span>
    </li>
  );
}

/**
 * One tab's rows. In the In tab the rows are numbered and, when more than `max` are in, a cut line follows row `max`
 * and the rows below it are dimmed.
 */
export default function ChristmasList({ rows, tab, max }) {
  const numbered = tab === 'inWeek';
  const cutAt = numbered ? cutIndex(rows, max) : rows.length;
  const cutCount = rows.length - cutAt;
  const items = [];
  rows.forEach((row, i) => {
    if (numbered && cutCount > 0 && i === cutAt) {
      items.push(
        <li key="cut" className="xmas-cut">
          <span className="xmas-cut__rule" aria-hidden="true" />
          <span>{cutText(max, cutCount)}</span>
        </li>,
      );
    }
    items.push(
      <ChristmasRow
        key={row.filmId}
        row={row}
        number={numbered ? i + 1 : null}
        cut={numbered && i >= cutAt}
        tab={tab}
      />,
    );
  });
  return <ul className="xmas-list">{items}</ul>;
}
