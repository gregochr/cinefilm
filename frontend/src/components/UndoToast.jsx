/** The Undo pill (bottom centre). The live region is always present so a new message is announced. */
export default function UndoToast({ action, onUndo, undoRef }) {
  const text = action
    ? `${action.film.title} ${action.kind === 'remove' ? 'removed from Continue watching' : 'marked as watched'}`
    : null;
  return (
    <div className="undo-toast-region" role="status">
      {action ? (
        <div className="undo-toast">
          <span className="undo-toast__text">{text}</span>
          <button type="button" className="undo-toast__btn" ref={undoRef} onClick={onUndo}>
            Undo
          </button>
        </div>
      ) : null}
    </div>
  );
}
