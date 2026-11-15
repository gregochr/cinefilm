import { rowTitle } from '../../state/adminTodo.js';

/** "Heat · 1995": the title with its year muted (AR1); a year that is only a guess carries a question mark. */
export default function RowText({ row, line }) {
  const { title, year } = rowTitle(row);
  return (
    <div className="admin__todo-text">
      <p className="admin__todo-title">
        {title}
        {year ? <span className="admin__todo-year"> · {year}</span> : null}
      </p>
      <p className="admin__todo-line">{line}</p>
    </div>
  );
}
