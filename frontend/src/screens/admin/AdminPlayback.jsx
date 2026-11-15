import ConversionPanel from '../../components/ConversionPanel.jsx';
import PlaybackChartPanel from '../../components/PlaybackChartPanel.jsx';
import PlaybackLogPanel from '../../components/PlaybackLogPanel.jsx';
import PlayingNowPanel from '../../components/PlayingNowPanel.jsx';
import RememberedNetworksPanel from '../../components/RememberedNetworksPanel.jsx';
import SectionTitle from './SectionTitle.jsx';
import { useTranscoding } from '../../state/useTranscoding.js';

/**
 * Playback: what is playing now, how films played, the playback log, conversion and the networks this browser
 * remembers. The transcoding state (live sessions, encoder, seek index) is read once here and shared by "Now playing"
 * (which matches the sessions to a stream by edition, to offer Stop) and Conversion.
 */
export default function AdminPlayback() {
  const transcoding = useTranscoding();
  return (
    <>
      <SectionTitle>Playback</SectionTitle>
      <div className="admin__grid">
        <PlayingNowPanel transcoding={transcoding} />
        <PlaybackChartPanel />
        <PlaybackLogPanel />
        <ConversionPanel state={transcoding} />
        <RememberedNetworksPanel />
      </div>
    </>
  );
}
