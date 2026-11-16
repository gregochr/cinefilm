import { rowTitle } from '../../state/adminTodo.js';

/**
 * "Heat · 1995": the title with its year muted (AR1); a year that is only a guess carries a question mark. `titleId`
 * is the title's id, so the row's buttons can name their film with `aria-describedby`.
 */
export default function RowText({ row, line, titleId }) {
  const { title, year } = rowTitle(row);
  return (
    <div className="admin__todo-text">
      <p id={titleId} className="admin__todo-title">
        {title}
        {year ? <span className="admin__todo-year"> · {year}</span> : null}
      </p>
      <p className="admin__todo-line">{line}</p>
    </div>
  );
}
