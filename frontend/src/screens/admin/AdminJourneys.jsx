import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAdminContext } from '../../state/adminContext.js';
import {
  journeyDelete as deleteCopy,
  journeyTrainRemove as removeCopy,
  journeysPage,
} from '../../state/journeysCopy.js';
import { rowAfterDelete, trainToast } from '../../state/journeysFormat.js';
import { useJourneyDelete } from '../../state/useJourneyDelete.js';
import { useJourneyTrain } from '../../state/useJourneyTrain.js';
import { useJourneys } from '../../state/useJourneys.js';
import JourneyImport from './JourneyImport.jsx';
import { deleteButtonId } from './JourneyDelete.jsx';
import JourneyList from './JourneyList.jsx';
import SectionTitle from './SectionTitle.jsx';
import { useFallbackFocus } from './useFallbackFocus.js';

/**
 * Train journeys, opened from Playback's card: the import of a logger's file, and the journeys stored, each with its
 * "Delete…" and its "Add train file…" or "Replace train file…" (and, with a train, "Remove train…"). One confirmation or
 * one train panel is open at a time on the whole page, and one write runs at a time. A delete that is done (or that finds the journey already gone) says
 * so in a toast and reads the list again; when the rows have arrived, focus goes to the journey that followed the one
 * deleted, else the one before it, else (no row left) Admin's fallback takes it to the page's title.
 */
export default function AdminJourneys() {
  const { journeys, loading, error, reload } = useJourneys();
  const { showToast, armFocusFallback, takeFocusFallback } = useAdminContext();
  const writer = useJourneyDelete();
  const trainer = useJourneyTrain();
  // The one thing open on the page: `{ kind: 'delete' | 'train' | 'remove', id }`, a row's delete
  // confirmation, its train panel or its train removal's confirmation.
  const [opened, setOpened] = useState(null);
  // The delete or train write that has been answered (a journey gone) and whose list is being read again: the id and
  // the list as it was.
  const pending = useRef(null);
  // The latest list read, for a write that is answered after the list has changed.
  const latest = useRef(journeys);
  latest.current = journeys;

  // Every way the owner changes what is open comes through here: what was in the other (a chosen file, a refusal) goes.
  const show = (next) => {
    writer.clearFailure();
    trainer.clearFailure();
    setOpened(next);
  };
  // A removal's confirmation whose train is no longer in the list (taken away in another tab, seen at a read again) is
  // closed and forgotten: it does not come back open when that journey is shown with a train again.
  if (opened?.kind === 'remove' && !journeys?.some((j) => j.id === opened.id && j.train)) {
    show(null);
  }
  const openIn = (kind) => (opened?.kind === kind ? opened.id : null);
  // Only one write runs at a time on the page: a row's button does nothing while either runs.
  const busy = writer.busy || trainer.busy;

  // A write is never cancelled (the server may have made it). A refusal shows in the journey's confirmation, which stays
  // open. A 204, or a 404 (already gone), says so, closes the confirmation and reads the list again. The fallback is
  // armed first so that a list with no row left lands on the page's title.
  const remove = async (id) => {
    armFocusFallback();
    const outcome = await writer.run(id);
    if (outcome.ignored) return;
    if (!outcome.ok && outcome.failure.kind !== 'gone') {
      takeFocusFallback();
      return;
    }
    // No other panel can have been opened meanwhile (a press does nothing while a write runs): this one closes.
    answered(id, outcome.ok ? deleteCopy.toast : deleteCopy.goneToast);
  };

  // A write that has been answered (a journey deleted, found already gone, or given its train): said in a toast, its
  // panel closed, the list read again. A row that is gone from the list is then told by the effect below.
  const answered = (id, message) => {
    showToast(message);
    setOpened(null);
    // The list as it is now, not as it was at the press: an import may have been read meanwhile, and it may have spent
    // the fallback armed before (it found focus on "Deleting…"), so it is armed again.
    pending.current = { id, list: latest.current };
    armFocusFallback();
    reload();
  };

  // The same for the train's file: a 200 says what was stored (the row then shows the train); a 404 is a journey that is
  // gone; any other refusal shows in the panel, which stays open.
  const give = async (id, request, replaced) => {
    const outcome = await trainer.give(id, request);
    if (outcome.ignored) return;
    if (outcome.ok) answered(id, trainToast(outcome.response, replaced));
    else if (outcome.failure.kind === 'gone') answered(id, deleteCopy.goneToast);
  };

  // The train taken away: a 204 says so; a 404 is a journey that is gone; any other refusal shows in the confirmation,
  // which stays open.
  const takeAway = async (id) => {
    const outcome = await trainer.takeAway(id);
    if (outcome.ignored) return;
    if (outcome.ok) answered(id, removeCopy.toast);
    else if (outcome.failure.kind === 'gone') answered(id, deleteCopy.goneToast);
  };

  // The list has been read again after a journey was answered as gone (a delete, a 404) or given its train (a 200): if
  // focus has fallen to the page, it goes to the next journey's "Delete…", else the previous one's; focus the owner has
  // put somewhere real since stays. A journey that is still there leaves focus alone. When the read failed, the list's
  // heading takes focus (`JourneyList`) and the fallback is spent here. With no row left, `useFallbackFocus` (armed in
  // `answered`) takes it to the page's title.
  useEffect(() => {
    const p = pending.current;
    if (!p || loading || (journeys === p.list && !error)) return;
    pending.current = null;
    if (error) {
      // The banner has replaced the rows and the list takes focus to its heading (`JourneyList`); the fallback is spent.
      takeFocusFallback();
      return;
    }
    if (document.activeElement !== document.body) return;
    const target = rowAfterDelete(p.list, p.id, journeys);
    if (target !== null) document.getElementById(deleteButtonId(target)).focus();
  }, [journeys, loading, error, takeFocusFallback]);
  const [settled, setSettled] = useState(journeys);
  if (!loading && journeys !== settled) setSettled(journeys);
  useFallbackFocus(settled);

  return (
    <>
      <Link to="/admin/playback" className="admin__back">
        {journeysPage.back}
      </Link>
      <SectionTitle>{journeysPage.title}</SectionTitle>
      <JourneyImport onImported={reload} />
      <JourneyList
        journeys={journeys}
        loading={loading}
        error={error}
        onRetry={reload}
        deleting={{
          openId: openIn('delete'),
          busy,
          failure: writer.failure,
          onOpen: (id) => show({ kind: 'delete', id }),
          onKeep: () => show(null),
          onConfirm: remove,
        }}
        training={{
          openId: openIn('train'),
          busy,
          failure: trainer.failure,
          onOpen: (id) => show({ kind: 'train', id }),
          onClose: () => show(null),
          onGive: give,
          onEdit: trainer.clearFailure,
        }}
        removing={{
          openId: openIn('remove'),
          busy,
          failure: trainer.failure,
          onOpen: (id) => show({ kind: 'remove', id }),
          onKeep: () => show(null),
          onConfirm: takeAway,
        }}
      />
    </>
  );
}
