import { useId } from 'react';
import { editionLabel, filmLabel, partOptions } from '../state/subtitleAdmin.js';
import { useAttachPicker } from '../state/useAttachPicker.js';
import { Button } from './Button.jsx';

/**
 * "Attach to…" for one subtitle file: find the film, pick its edition and (for a split edition) the whole film or
 * one part. Nothing is sent until "Attach".
 */
export default function AttachPicker({ name, busy, onAttach, onCancel }) {
  const picker = useAttachPicker();
  const group = useId();
  return (
    <form
      className="subs__attach"
      aria-label={`Attach ${name} to a film`}
      onSubmit={(event) => {
        event.preventDefault();
        if (picker.target) onAttach(picker.target);
      }}
    >
      <label className="fixmatch__field">
        Find a film
        <input
          type="search"
          className="fixmatch__input"
          value={picker.query}
          onChange={(event) => picker.setQuery(event.target.value)}
        />
      </label>
      {picker.searching && picker.searchError ? (
        <p className="admin__warning" role="alert">
          Couldn&rsquo;t search the library. Try again.
        </p>
      ) : null}
      {picker.searching && !picker.searchError && picker.results.length === 0 ? (
        <p className="admin__text" role="status">
          {picker.searchLoading ? 'Searching…' : 'No film by that name.'}
        </p>
      ) : null}
      {picker.results.length > 0 ? (
        <fieldset className="subs__choices">
          <legend className="admin__text">Film</legend>
          {picker.results.map((film) => (
            <label key={film.id} className="subs__choice">
              <input
                type="radio"
                name={`${group}-film`}
                checked={picker.filmId === film.id}
                onChange={() => picker.pickFilm(film.id)}
              />
              <span>{filmLabel(film)}</span>
            </label>
          ))}
        </fieldset>
      ) : null}
      {picker.editionsLoading ? (
        <p className="admin__text" role="status">
          Loading editions…
        </p>
      ) : null}
      {picker.editionsError ? (
        <div role="alert">
          <p className="admin__warning">Couldn&rsquo;t load that film&rsquo;s editions.</p>
          <Button onClick={picker.retryEditions}>Try again</Button>
        </div>
      ) : null}
      {picker.editions.length > 1 ? (
        <fieldset className="subs__choices">
          <legend className="admin__text">Edition</legend>
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
          <legend className="admin__text">Timed for</legend>
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
      <div className="admin__actions">
        <Button type="submit" variant="primary" disabled={!picker.target || busy}>
          {busy ? 'Attaching…' : 'Attach'}
        </Button>
        <Button onClick={onCancel}>Cancel</Button>
      </div>
    </form>
  );
}
