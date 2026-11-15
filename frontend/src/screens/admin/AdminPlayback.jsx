import PlaybackHealthPanel from '../../components/PlaybackHealthPanel.jsx';
import PlayingNowPanel from '../../components/PlayingNowPanel.jsx';
import SectionTitle from './SectionTitle.jsx';
import TranscodingPanel from '../../components/TranscodingPanel.jsx';
import { useAdminContext } from '../../state/adminContext.js';
import { useTranscoding } from '../../state/useTranscoding.js';

/**
 * Playback: what is playing now, what is being transcoded and how recent playbacks went. The live sessions are read
 * once here and shared by "Now playing" (which matches them to a stream by edition, to offer Stop) and Transcoding.
 */
export default function AdminPlayback() {
  const { adminSummary } = useAdminContext();
  const transcoding = useTranscoding({ onStopped: adminSummary.reload });
  return (
    <>
      <SectionTitle>Playback</SectionTitle>
      <div className="admin__grid">
        <PlayingNowPanel transcoding={transcoding} />
        <TranscodingPanel state={transcoding} />
        <PlaybackHealthPanel />
      </div>
    </>
  );
}
