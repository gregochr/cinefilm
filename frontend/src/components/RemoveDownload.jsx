import { useEffect, useRef, useState } from 'react';
import { REMOVE_CONFIRM } from '../state/subtitleAdmin.js';
import { adminRemoveLabels } from '../state/subtitleFixCopy.js';
import { ActionWithHint } from './ActionWithHint.jsx';
import { Button } from './Button.jsx';

/**
 * Removing a download: an opening button, then an inline confirmation. Focus goes to the safe choice, and back on
 * cancel. The server blocks the same file for that edition, which is what the confirmation says. `labels` is the
 * wording (Admin's old words by default; Film detail's Fix… panel passes "Delete this download"); a `hint` puts the
 * visible hint line under the opening button. `onRemoved` runs once the download is gone.
 */
export default function RemoveDownload({
  item,
  name,
  busy,
  failed,
  actions,
  onRemoved,
  labels = adminRemoveLabels,
  hint,
}) {
  const [confirming, setConfirming] = useState(false);
  const removeRef = useRef(null);
  const keepRef = useRef(null);
  const returnFocus = useRef(false);
  useEffect(() => {
    if (confirming) keepRef.current?.focus();
    else if (returnFocus.current) {
      returnFocus.current = false;
      removeRef.current?.focus();
    }
  }, [confirming]);
  const confirm = async () => {
    if (await actions.remove(item.id)) onRemoved();
  };
  const cancel = () => {
    returnFocus.current = true;
    setConfirming(false);
  };
  if (!confirming) {
    const open = {
      ref: removeRef,
      'aria-label': labels.openSpoken(name),
      disabled: busy,
      onClick: () => setConfirming(true),
    };
    return hint ? (
      <ActionWithHint layout="line" hint={hint} {...open}>
        {labels.open}
      </ActionWithHint>
    ) : (
      <Button {...open}>{labels.open}</Button>
    );
  }
  return (
    <div className="subs__confirm" role="group" aria-label={labels.group}>
      <p className="admin__text">{REMOVE_CONFIRM}</p>
      <div className="admin__actions">
        <Button disabled={busy} onClick={confirm}>
          {failed ? labels.tryAgain : labels.confirm}
        </Button>
        <Button ref={keepRef} disabled={busy} onClick={cancel}>
          {labels.keep}
        </Button>
      </div>
    </div>
  );
}
