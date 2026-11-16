import { useLocation, useNavigate } from 'react-router-dom';
import { subtitlesPage } from '../../state/adminCopy.js';
import { useAdminContext } from '../../state/adminContext.js';
import { everythingElse } from '../../state/adminSubtitles.js';
import { useAdminSubtitles } from '../../state/useAdminSubtitles.js';
import AdminTabList from './AdminTabList.jsx';
import SectionTitle from './SectionTitle.jsx';
import SubtitleEverythingElse, { Rich } from './SubtitleEverythingElse.jsx';
import SubtitleNeedPanel from './SubtitleNeedPanel.jsx';

/**
 * Subtitles: two tabs (the foreign-language films with no English subtitles, the English films with foreign scenes
 * and no forced subtitle found), each listing only the films the server flagged `unresolved`, and "Everything else".
 * Each list loads on its own, so one failing leaves the other tab and the rest of the page on screen. Every owner
 * choice calls `markChanged`, so the summary, the rail counts, the To do groups and these lists read again; so does
 * a file picked in the OpenSubtitles picker, which the layout hosts (`openSubtitlePicker`).
 */
export default function AdminSubtitles() {
  const { subtitlesVersion, changed, markChanged, scan, openSubtitlePicker } = useAdminContext();
  // The open tab lives in the address (`#foreign`, `#forced`), so the To do footer can link to the forced one and a
  // reload or a shared link lands on the same tab. A tab change replaces the entry: switching tabs is not a page.
  const { hash, search } = useLocation();
  const navigate = useNavigate();
  const tab = hash === '#forced' ? 'forced' : 'foreign';
  const setTab = (key) => navigate({ search, hash: `#${key}` }, { replace: true });
  const lists = useAdminSubtitles({ subtitlesVersion, changed, onChanged: markChanged });
  const figures = everythingElse({
    fullRows: lists.full.rows,
    fullCounts: lists.full.counts,
    forcedCounts: lists.forced.counts,
  });
  const tabs = [
    { key: 'foreign', label: subtitlesPage.tabs.foreign, count: lists.foreign?.length ?? null },
    { key: 'forced', label: subtitlesPage.tabs.forced, count: lists.forcedFilms?.length ?? null },
  ];
  return (
    <>
      <SectionTitle>Subtitles</SectionTitle>
      <p className="admin__lead">
        <Rich parts={subtitlesPage.lead} />
      </p>
      <AdminTabList label={subtitlesPage.tabsLabel} tabs={tabs} selected={tab} onSelect={setTab} />
      <SubtitleNeedPanel
        kind="foreign"
        active={tab === 'foreign'}
        need={lists.full}
        rows={lists.foreign}
        onChanged={markChanged}
        onFind={openSubtitlePicker}
      />
      <SubtitleNeedPanel
        kind="forced"
        active={tab === 'forced'}
        need={lists.forced}
        rows={lists.forcedFilms}
        onChanged={markChanged}
        onFind={openSubtitlePicker}
      />
      <SubtitleEverythingElse
        figures={figures}
        needs={{ full: lists.full, forced: lists.forced }}
        scan={scan}
        // Any change markChanged signals (a choice, an attached file) re-reads an open file list too.
        version={`${subtitlesVersion}|${changed}`}
      />
    </>
  );
}
