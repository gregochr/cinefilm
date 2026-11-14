import PlaybackHealthPanel from '../../components/PlaybackHealthPanel.jsx';
import SectionTitle from './SectionTitle.jsx';
import TranscodingPanel from '../../components/TranscodingPanel.jsx';

/** Playback: what is being transcoded and how recent playbacks went. */
export default function AdminPlayback() {
  return (
    <>
      <SectionTitle>Playback</SectionTitle>
      <div className="admin__grid">
        <TranscodingPanel />
        <PlaybackHealthPanel />
      </div>
    </>
  );
}
