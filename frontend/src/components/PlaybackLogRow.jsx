import { useAdminContext } from '../state/adminContext.js';
import { playbackLog as copy } from '../state/adminCopy.js';
import { copyRawReport, logRow, reportDetails } from '../state/playbackLog.js';
import { Button } from './Button.jsx';

/** The columns' count: the seven of the table and the Details button's. */
export const LOG_COLUMNS = copy.columns.length + 1;

/**
 * One playback of the log: its table row and, when `open`, a second row of Details in words (never the report's JSON)
 * with "Copy raw report". On the phone the rows are stacked cards (CSS only; the cells carry `data-label`).
 */
export default function PlaybackLogRow({ row, open, onToggle }) {
  const { showToast } = useAdminContext();
  const cells = logRow(row);
  const detailsId = `plog-details-${cells.id}`;
  const label = open ? copy.hideDetails : copy.details;
  const copyReport = async () =>
    showToast((await copyRawReport(row)) ? copy.copied : copy.copyFailed);
  return (
    <>
      <tr className="plog__row">
        <td className="plog__started admin__mono" data-label={copy.columns[0]}>
          {cells.started}
        </td>
        <td className="plog__film">
          {cells.film}
          {cells.browsing ? <span className="plog__pill">{copy.browsing}</span> : null}
        </td>
        <td className="admin__mono" data-label={copy.columns[2]}>
          {cells.watched}
        </td>
        <td data-label={copy.columns[3]}>{cells.where}</td>
        <td className="admin__mono" data-label={copy.columns[4]}>
          {cells.start}
        </td>
        <td
          className={`admin__mono${cells.stalled ? ' plog__stalls--err' : ''}`}
          data-label={copy.columns[5]}
        >
          {cells.stalls}
        </td>
        <td className="admin__mono" data-label={copy.columns[6]}>
          {cells.quality}
        </td>
        <td className="plog__action">
          <Button
            aria-expanded={open}
            aria-controls={detailsId}
            aria-label={copy.buttonName(label, cells.film, cells.started)}
            onClick={onToggle}
          >
            {label}
          </Button>
        </td>
      </tr>
      {open ? (
        <tr id={detailsId} className="plog__details">
          <td colSpan={LOG_COLUMNS}>
            <dl className="plog__kv">
              {reportDetails(row).map(({ key, label: term, value }) => (
                <div key={key} className="plog__kv-row">
                  <dt>{term}</dt>
                  <dd>{value}</dd>
                </div>
              ))}
            </dl>
            <Button onClick={copyReport}>{copy.copyReport}</Button>
          </td>
        </tr>
      ) : null}
    </>
  );
}
