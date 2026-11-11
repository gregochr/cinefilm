import { useCallback, useEffect, useRef, useState } from 'react';
import { settingsApi } from '../api/resources.js';
import { useResource } from './useResource.js';

export const MIN_MINUTES = 0;
export const MAX_MINUTES = 30;
export const RANGE_ERROR = `Enter a whole number of minutes from ${MIN_MINUTES} to ${MAX_MINUTES}.`;
const SAVE_ERROR = 'Could not save the setting.';

/** The minutes a typed value stands for, or null when it is not a whole number from 0 to 30. */
export function parseMinutes(text) {
  if (!/^\d{1,3}$/.test(text.trim())) return null;
  const minutes = Number(text);
  return minutes >= MIN_MINUTES && minutes <= MAX_MINUTES ? minutes : null;
}

/** "1 film currently in progress", "12 films currently in progress". */
export function describeInProgress(count) {
  return `${count} film${count === 1 ? '' : 's'} currently in progress`;
}

/**
 * The Continue watching setting on Admin: the minimum watch time in minutes and how many films are in progress at
 * it. `commit(text)` (call it on blur or Enter) saves a changed, valid value and sends nothing otherwise. Only the
 * newest save may change what is shown: an older response arriving later is ignored. When the newest save fails
 * the input goes back to the last saved value, and the settings are read again in case an older save did land.
 */
export function useSettings() {
  const {
    data,
    error: loadError,
    loading,
    reload,
  } = useResource('admin-settings', (options) => settingsApi.get(options));
  const [saved, setSaved] = useState(null); // the newest PUT response, over the loaded settings
  const [draft, setDraft] = useState(null); // what is typed, or null to show the saved value
  const [error, setError] = useState(null);
  const [status, setStatus] = useState(null);
  const latest = useRef(0); // the number of the newest save
  const draftRev = useRef(0); // advanced by every edit, so a save only clears the draft it was made from
  const inFlight = useRef(0);
  const chain = useRef(Promise.resolve()); // saves run one after another, so the server ends on the last one sent
  const intended = useRef(null); // the minutes the server will have once the saves in flight land

  const view = saved ?? data;
  // Settings read from the server are the truth, unless a save is still on its way.
  useEffect(() => {
    if (!data || inFlight.current > 0) return;
    intended.current = data.continueWatchingMinMinutes;
    setSaved(null);
  }, [data]);
  useEffect(
    () => () => {
      latest.current += 1; // nothing that is still in flight may set state after unmount
    },
    [],
  );

  const edit = useCallback((text) => {
    draftRev.current += 1;
    setDraft(text);
    setError(null);
    setStatus(null);
  }, []);

  const commit = useCallback(
    async (text) => {
      if (text === null || !view) return;
      const minutes = parseMinutes(text);
      if (minutes === null) {
        setError(RANGE_ERROR);
        setStatus(null);
        return;
      }
      setError(null);
      if (minutes === intended.current) {
        setDraft(null);
        return;
      }
      const lastSaved = view.continueWatchingMinMinutes;
      intended.current = minutes;
      latest.current += 1;
      inFlight.current += 1;
      const mine = latest.current;
      const rev = draftRev.current;
      setStatus(null);
      try {
        const request = chain.current.then(() => settingsApi.update(minutes));
        chain.current = request.catch(() => {});
        const response = await request;
        if (mine !== latest.current) return;
        intended.current = response.continueWatchingMinMinutes;
        setSaved(response);
        if (draftRev.current === rev) setDraft(null); // typing since then stays
        setStatus('Saved');
      } catch {
        if (mine !== latest.current) return;
        intended.current = lastSaved;
        if (draftRev.current === rev) setDraft(null);
        setError(SAVE_ERROR);
        reload();
      } finally {
        inFlight.current -= 1;
      }
    },
    [view, reload],
  );

  return { settings: view, loadError, loading, reload, draft, edit, commit, error, status };
}
