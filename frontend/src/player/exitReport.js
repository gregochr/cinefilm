import { useEffect, useRef } from 'react';
import { editionsApi } from '../api/resources.js';

/**
 * Reports progress when the page is hidden or closed, using a keepalive request so it
 * survives the tab closing. DOM listeners live here, keeping src/state DOM-free.
 */
export function usePageExitReport({ editionId, getPositionMs }) {
  const getRef = useRef(getPositionMs);
  useEffect(() => {
    getRef.current = getPositionMs;
  });

  useEffect(() => {
    const send = () => {
      const positionMs = Math.round(getRef.current());
      if (!editionId || !Number.isFinite(positionMs) || positionMs <= 0) return;
      editionsApi.reportProgress(editionId, positionMs, { keepalive: true }).catch(() => {});
    };
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') send();
    };
    window.addEventListener('pagehide', send);
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      window.removeEventListener('pagehide', send);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [editionId]);
}
