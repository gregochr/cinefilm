import { Link } from 'react-router-dom';
import { SkeletonBlock } from '../../components/Skeleton.jsx';
import AdminErrorBanner from '../../components/AdminErrorBanner.jsx';
import { libraryPage } from '../../state/adminCopy.js';
import { useAdminContext } from '../../state/adminContext.js';
import { kindLabel, titleWithYear } from '../../state/scanFormat.js';
import { useAttention } from '../../state/useAdmin.js';
import SectionTitle from './SectionTitle.jsx';

/**
 * A plain list of the films the last scan could not make playable, opened from a Library count tile: the films of
 * one attention kind (protected, needs conversion or unreadable), each with the server's reason and its file names.
 * A title links to the film's page only when the server says the film has one (`browsable`: a film whose every
 * edition is unplayable has none). `list` is one entry of `FILE_LISTS`.
 */
export default function AdminLibraryFiles({ list }) {
  const { settledAt, changed } = useAdminContext();
  const attention = useAttention(`${settledAt}|${changed}`);
  const items = (attention.data?.items ?? []).filter((item) => item.kind === list.kind);
  let body;
  if (!attention.data && attention.loading) {
    body = (
      <div role="status" aria-label="Loading">
        <SkeletonBlock style={{ height: 60 }} />
      </div>
    );
  } else if (!attention.data) {
    body = (
      <AdminErrorBanner onRetry={attention.reload}>{libraryPage.files.loadError}</AdminErrorBanner>
    );
  } else if (items.length === 0) {
    body = <p className="admin__text">{libraryPage.files.empty}</p>;
  } else {
    body = (
      <ul className="admin__list" aria-label={list.title}>
        {items.map((item, i) => (
          <li key={`${item.filmId}-${i}-${item.files.join('|')}`} className="admin__item">
            <div className="admin__item-head">
              {item.browsable ? (
                <Link to={`/films/${item.filmId}`} className="admin__item-title">
                  {titleWithYear(item.title, item.year)}
                </Link>
              ) : (
                <span className="admin__item-title">{titleWithYear(item.title, item.year)}</span>
              )}
              <span className="admin__tag">{kindLabel(item.kind)}</span>
            </div>
            <div className="admin__item-detail">{item.detail}</div>
            <div className="admin__item-files admin__mono">{item.files.join(', ')}</div>
          </li>
        ))}
      </ul>
    );
  }
  return (
    <>
      <Link to="/admin/library" className="admin__back">
        {libraryPage.files.back}
      </Link>
      <SectionTitle>{list.title}</SectionTitle>
      <section className="admin__card" aria-labelledby="admin-section-title">
        {body}
      </section>
    </>
  );
}
