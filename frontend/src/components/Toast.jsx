/** A short message pill (bottom centre). The live region is always present so a new message is announced. */
export default function Toast({ message }) {
  return (
    <div className="undo-toast-region" role="status">
      {message ? (
        <div className="undo-toast undo-toast--plain">
          <span className="undo-toast__text">{message}</span>
        </div>
      ) : null}
    </div>
  );
}
