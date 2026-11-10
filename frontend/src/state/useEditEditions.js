import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { adminApi } from '../api/resources.js';
import {
  describeModel,
  initialModel,
  saveProblem,
  setDefault,
  setEdition,
  setNewName,
  setNot,
  setPart,
} from './editEditions.js';

/**
 * The Edit editions panel for one film: loads the files and the suggestion, keeps what the owner arranges, and saves
 * it. A failed save keeps every edit and says why; the server's field errors stay until the next edit. `onSaved`
 * runs with the save's answer once the server has accepted it.
 */
export function useEditEditions(filmId, onSaved) {
  const [load, setLoad] = useState({ status: 'loading', error: null });
  const [model, setModel] = useState(null);
  const [serverErrors, setServerErrors] = useState([]);
  const [saving, setSaving] = useState(false);
  const [problem, setProblem] = useState(null);
  const [attempt, setAttempt] = useState(0);
  // Set the moment a save starts, so an edit made before the next render is ignored as well.
  const inFlight = useRef(false);

  useEffect(() => {
    const controller = new AbortController();
    setLoad({ status: 'loading', error: null });
    adminApi
      .editions(filmId, { signal: controller.signal })
      .then((view) => {
        if (controller.signal.aborted) return;
        setModel(initialModel(view));
        setLoad({ status: 'ready', error: null });
      })
      .catch((error) => {
        if (controller.signal.aborted || error?.name === 'AbortError') return;
        setLoad({ status: 'error', error });
      });
    return () => controller.abort();
  }, [filmId, attempt]);

  const view = useMemo(
    () => (model ? describeModel(model, serverErrors) : null),
    [model, serverErrors],
  );

  const edit = useCallback((change) => {
    if (inFlight.current) return;
    setModel((m) => change(m));
    setServerErrors([]);
  }, []);

  const save = useCallback(async () => {
    if (!view || view.errorCount > 0 || inFlight.current) return;
    inFlight.current = true;
    setSaving(true);
    setProblem(null);
    try {
      const result = await adminApi.saveEditions(filmId, view.request);
      onSaved(result);
    } catch (failure) {
      const failed = saveProblem(failure);
      setProblem(failed.text);
      setServerErrors(failed.fieldErrors);
    } finally {
      inFlight.current = false;
      setSaving(false);
    }
  }, [view, filmId, onSaved]);

  return {
    status: load.status,
    reason: model?.reason ?? '',
    fixed: model?.fixed ?? [],
    view,
    saving,
    problem,
    setEdition: (fileId, edition) => edit((m) => setEdition(m, fileId, edition)),
    setNewName: (fileId, text) => edit((m) => setNewName(m, fileId, text)),
    setPart: (fileId, part) => edit((m) => setPart(m, fileId, part)),
    setNot: (fileId, not) => edit((m) => setNot(m, fileId, not)),
    setDefault: (name) => edit((m) => setDefault(m, name)),
    save,
    retry: () => setAttempt((n) => n + 1),
  };
}
