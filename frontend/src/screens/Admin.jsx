const PANELS = [
  ['Plex import', 'Bring films, posters and watch history across from Plex.'],
  ['Library scan', 'Point CineFilm at a folder and scan for new films.'],
  ['Transcode worker', 'Online status and active sessions.'],
  ['Pre-made versions', 'Travel copies prepared ahead of time.'],
  ['Films needing attention', 'Unmatched files, and files that should be grouped as editions.'],
  ['Fix match', 'Search TMDB and pick the right film.'],
];

/** Placeholder until phase 2: the panels the handover describes, not yet wired up. */
export default function Admin() {
  return (
    <div className="page__stack">
      <h1 className="page-title">Admin</h1>
      <p className="state__text">Admin arrives with the real library in a later phase.</p>
      <div className="admin__grid">
        {PANELS.map(([title, text]) => (
          <section key={title} className="admin__card">
            <h2 className="admin__title">{title}</h2>
            <p className="admin__text">{text}</p>
          </section>
        ))}
      </div>
    </div>
  );
}
