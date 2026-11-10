import { imageUrl } from '../api/images.js';
import { useFixMatch } from '../state/useFixMatch.js';
import { resultPeople, resultRuntime, resultTitle } from '../state/fixMatch.js';
import { Button } from './Button.jsx';
import { SkeletonBlock } from './Skeleton.jsx';

function Results({ search, picked, onPick }) {
  if (search.status === 'loading') {
    return (
      <div role="status" aria-label="Searching TMDB">
        <SkeletonBlock style={{ height: 96 }} />
      </div>
    );
  }
  if (search.status === 'error') {
    return (
      <p className="admin__warning" role="alert">
        {search.problem}
      </p>
    );
  }
  if (search.results.length === 0) {
    return <p className="admin__text">TMDB has nothing for that. Try another title or year.</p>;
  }
  return (
    <fieldset className="fixmatch__results">
      <legend className="admin__text">Pick the right film</legend>
      {search.results.map((result) => {
        const poster = imageUrl(result.posterPath);
        const runtime = resultRuntime(result);
        return (
          <label
            key={result.tmdbId}
            className={`fixmatch__result${picked === result.tmdbId ? ' fixmatch__result--on' : ''}`}
          >
            <input
              type="radio"
              name="match"
              checked={picked === result.tmdbId}
              onChange={() => onPick(result.tmdbId)}
            />
            {poster ? (
              <img className="fixmatch__poster" src={poster} alt="" loading="lazy" />
            ) : (
              <span className="fixmatch__poster" aria-hidden="true" />
            )}
            <span className="fixmatch__text">
              <span className="fixmatch__name">{resultTitle(result)}</span>
              <span className="admin__text">{resultPeople(result)}</span>
            </span>
            {runtime ? <span className="admin__mono fixmatch__runtime">{runtime}</span> : null}
          </label>
        );
      })}
    </fieldset>
  );
}

/** The Fix match panel: search TMDB for a film that needs a match and pick the right one. */
export default function FixMatchPanel({ item, onApplied, onCancel }) {
  const fix = useFixMatch(item.filmId, item.title, onApplied);
  const { search } = fix;
  return (
    <section className="admin__card fixmatch" aria-labelledby="fix-match-title">
      <div className="fixmatch__head">
        <h2 id="fix-match-title" className="fixmatch__title">
          Fix match
        </h2>
        <span className="admin__mono admin__text">File: {item.files.join(' · ')}</span>
      </div>
      <form
        className="fixmatch__form"
        onSubmit={(event) => {
          event.preventDefault();
          fix.submit();
        }}
      >
        <label className="fixmatch__field fixmatch__field--query">
          Search TMDB
          <input
            type="search"
            className="fixmatch__input"
            value={fix.query}
            onChange={(event) => fix.setQuery(event.target.value)}
          />
        </label>
        <label className="fixmatch__field fixmatch__field--year">
          Year (optional)
          <input
            type="text"
            inputMode="numeric"
            maxLength={4}
            className="fixmatch__input"
            value={fix.year}
            onChange={(event) => fix.setYear(event.target.value)}
          />
        </label>
        <Button type="submit" disabled={search.status === 'loading'}>
          Search
        </Button>
      </form>
      <Results search={search} picked={fix.picked} onPick={fix.setPicked} />
      {fix.applyProblem ? (
        <p className="admin__warning" role="alert">
          {fix.applyProblem}
        </p>
      ) : null}
      <div className="admin__actions">
        <Button variant="primary" disabled={fix.picked == null || fix.applying} onClick={fix.apply}>
          {fix.applying ? 'Applying…' : 'Use this match'}
        </Button>
        <Button onClick={onCancel}>Cancel</Button>
      </div>
    </section>
  );
}
