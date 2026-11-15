import { conversion as copy } from '../state/adminCopy.js';
import { encoderStatus, formatBytes, seekIndexText } from '../state/transcodingFormat.js';
import { SkeletonBlock } from './Skeleton.jsx';
import { ErrorState } from './StateMessages.jsx';

/**
 * Admin Playback: the encoder and the keyframe (seek) index. `state` is `useTranscoding()`, read by the Playback
 * section so "Now playing" shares it. "Pre-made versions" is left out: nothing in the API counts them.
 */
export default function ConversionPanel({ state }) {
  const { transcoding, error, loading, reload } = state;
  let body;
  if (loading && !transcoding) {
    body = (
      <div role="status" aria-label={copy.loadingLabel}>
        <SkeletonBlock style={{ height: 80 }} />
      </div>
    );
  } else if (error && !transcoding) {
    body = <ErrorState onRetry={reload}>{copy.loadError}</ErrorState>;
  } else if (transcoding) {
    body = (
      <dl className="admin__rows">
        <dt>{copy.seekIndex}</dt>
        <dd className="admin__mono">{seekIndexText(transcoding.index)}</dd>
        <dt>{copy.streamCache}</dt>
        <dd className="admin__mono">{formatBytes(transcoding.hlsBytes)}</dd>
      </dl>
    );
  }
  return (
    <section className="admin__card" aria-labelledby="admin-conversion-title">
      <div className="admin__head">
        <h2 id="admin-conversion-title" className="admin__title">
          {copy.title}
        </h2>
        {transcoding ? (
          <span className="admin__text">{encoderStatus(transcoding.encoder)}</span>
        ) : null}
      </div>
      {body}
    </section>
  );
}
