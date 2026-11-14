import { Button, ButtonLink } from './Button.jsx';
import LogoMark from './LogoMark.jsx';

export function EmptyLibrary() {
  return (
    <div className="state">
      <LogoMark size={64} />
      <h2 className="state__title">The house lights are up</h2>
      <p className="state__text">
        Nothing in the library yet. Bring your films, posters and watch history across from Plex, or
        point CineFilm at a folder.
      </p>
      <div className="state__actions">
        <ButtonLink variant="primary" to="/admin/library#plex-import">
          Import from Plex
        </ButtonLink>
        <ButtonLink to="/admin/library#scan">Scan a folder</ButtonLink>
      </div>
    </div>
  );
}

export function NoResults({
  query,
  onClear,
  noun = 'films',
  hint = 'Search looks at titles, actors and directors.',
}) {
  return (
    <div className="state">
      <h2 className="state__title">Nothing on the shelf</h2>
      <p className="state__text">
        No {noun} match {query ? `“${query}” ` : ''}with these filters. {hint}
      </p>
      <div className="state__actions">
        <Button onClick={onClear}>Clear search and filters</Button>
      </div>
    </div>
  );
}

export function ErrorState({ onRetry, children = 'Something went wrong loading this.' }) {
  return (
    <div className="state" role="alert">
      <LogoMark size={48} />
      <h2 className="state__title">The projector jammed</h2>
      <p className="state__text">{children}</p>
      {onRetry ? (
        <div className="state__actions">
          <Button onClick={onRetry}>Try again</Button>
        </div>
      ) : null}
    </div>
  );
}
