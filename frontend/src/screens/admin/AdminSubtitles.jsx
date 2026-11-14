import SubtitlesPanel from '../../components/SubtitlesPanel.jsx';
import SectionTitle from './SectionTitle.jsx';
import { useAdminContext } from '../../state/adminContext.js';

/** Subtitles: the forced and full subtitle needs, read again when a scan, a search or a check finishes. */
export default function AdminSubtitles() {
  const { subtitlesVersion } = useAdminContext();
  return (
    <>
      <SectionTitle>Subtitles</SectionTitle>
      <div className="admin__grid">
        <SubtitlesPanel version={subtitlesVersion} />
      </div>
    </>
  );
}
