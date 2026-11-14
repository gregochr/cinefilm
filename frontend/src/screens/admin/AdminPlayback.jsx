import PlaybackHealthPanel from '../../components/PlaybackHealthPanel.jsx';
import TranscodingPanel from '../../components/TranscodingPanel.jsx';

/** Playback: what is being transcoded and how recent playbacks went. */
export default function AdminPlayback() {
  return (
    <>
      <h1 className="admin__section-title">Playback</h1>
      <div className="admin__grid">
        <TranscodingPanel />
        <PlaybackHealthPanel />
      </div>
    </>
  );
}
