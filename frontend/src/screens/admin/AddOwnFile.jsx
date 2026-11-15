import { useEffect, useId } from 'react';
import { Button } from '../../components/Button.jsx';
import { subtitlesPage } from '../../state/adminCopy.js';
import { fileChoiceNote } from '../../state/adminSubtitles.js';
import { kindTag } from '../../state/subtitleFix.js';
import { editionLabel, fileName, partOptions } from '../../state/subtitleAdmin.js';
import { useFilmFilePicker } from '../../state/useFilmFilePicker.js';

const copy = subtitlesPage.addFile;

/** A line that says something went wrong, in the error colour, with its own way to try again. */
function Problem({ children, onRetry }) {
  return (
    <div className="admin__todo-error" role="alert">
      <p className="admin__todo-error-text">{children}</p>
      {onRetry ? <Button onClick={onRetry}>{subtitlesPage.retry}</Button> : null}
    </div>
  );
}

/**
 * "Add my own file…" for one film: choose one of the owner's subtitle files, the edition and (for a split edition) the
 * part, then "Attach". It is the existing attach route, scoped to the film. Only full files are offered, unless
 * `allKinds` (Film detail's Fix… panel): then forced files too, each labelled with its kind, and this film's own
 * files, to move one. `onAttached` runs once the server has accepted it; `onCancel` closes the form (not while an
 * attach is in flight); `onBusyChange(boolean)`, when given, says when an attach starts and ends. Nothing is sent
 * before "Attach".
 */
export default function AddOwnFile({
  filmId,
  title,
  onAttached,
  onCancel,
  onBusyChange,
  allKinds = false,
}) {
  const picker = useFilmFilePicker(filmId, { allKinds });
  // Tells the host while an attach is in flight, so it can keep this form open (its guard lives in this instance).
  useEffect(() => {
    onBusyChange?.(picker.attaching);
    // Unmounting (the attach finished and the form closed) ends it too.
    return () => onBusyChange?.(false);
  }, [picker.attaching, onBusyChange]);
  const group = useId();
  const submit = async (event) => {
    event.preventDefault();
    if (await picker.attach()) onAttached();
  };
  return (
    <form className="admin__addfile" aria-label={copy.heading(title)} onSubmit={submit}>
      {picker.filesLoading ? (
        <p className="admin__text" role="status">
          {copy.loading}
        </p>
      ) : null}
      {picker.filesError ? <Problem onRetry={picker.retryFiles}>{copy.loadError}</Problem> : null}
      {picker.files && picker.files.length === 0 ? (
        <p className="admin__text">{copy.none}</p>
      ) : null}
      {picker.files && picker.files.length > 0 ? (
        <fieldset className="subs__choices">
          <legend className="admin__text">{copy.file}</legend>
          {picker.files.map((file) => (
            <label key={file.id} className="subs__choice">
              <input
                type="radio"
                name={`${group}-file`}
                checked={picker.fileId === file.id}
                onChange={() => picker.pickFile(file.id)}
              />
              <span>
                {fileName(file.path)}{' '}
                <span className="admin__text">
                  · {allKinds ? `${kindTag(file)} · ` : ''}
                  {fileChoiceNote(file, filmId)}
                </span>
              </span>
            </label>
          ))}
        </fieldset>
      ) : null}
      {picker.editionsLoading ? (
        <p className="admin__text" role="status">
          {copy.loadingEditions}
        </p>
      ) : null}
      {!picker.editionsLoading && !picker.editionsError && picker.editions.length === 0 ? (
        <p className="admin__text">{copy.noEdition}</p>
      ) : null}
      {picker.editionsError ? (
        <Problem onRetry={picker.retryEditions}>{copy.editionsError}</Problem>
      ) : null}
      {picker.editions.length > 1 ? (
        <fieldset className="subs__choices">
          <legend className="admin__text">{copy.edition}</legend>
          {picker.editions.map((edition) => (
            <label key={edition.id} className="subs__choice">
              <input
                type="radio"
                name={`${group}-edition`}
                checked={picker.edition?.id === edition.id}
                onChange={() => picker.pickEdition(edition.id)}
              />
              <span>{editionLabel(edition)}</span>
            </label>
          ))}
        </fieldset>
      ) : null}
      {picker.edition && picker.edition.parts > 1 ? (
        <fieldset className="subs__choices">
          <legend className="admin__text">{copy.timedFor}</legend>
          {partOptions(picker.edition).map((option) => (
            <label key={option.label} className="subs__choice">
              <input
                type="radio"
                name={`${group}-part`}
                checked={picker.part === option.value}
                onChange={() => picker.pickPart(option.value)}
              />
              <span>{option.label}</span>
            </label>
          ))}
        </fieldset>
      ) : null}
      {picker.problem ? (
        <p className="admin__todo-problem" role="alert">
          {picker.problem}
        </p>
      ) : null}
      <div className="admin__actions">
        {/* aria-disabled, not disabled: the button keeps focus while its request runs; the hook ignores clicks. */}
        <Button
          type="submit"
          aria-disabled={!picker.target || picker.attaching || undefined}
          onClick={(event) => {
            if (!picker.target || picker.attaching) event.preventDefault();
          }}
        >
          {picker.attaching ? copy.attaching : copy.attach}
        </Button>
        {/* Not while an attach is in flight: closing the form then would let it be opened and sent again. */}
        <Button
          aria-disabled={picker.attaching || undefined}
          onClick={() => {
            if (!picker.attaching) onCancel();
          }}
        >
          {copy.cancel}
        </Button>
      </div>
    </form>
  );
}
