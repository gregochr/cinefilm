import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAdminContext } from '../../state/adminContext.js';
import { journeyDelete as deleteCopy, journeysPage } from '../../state/journeysCopy.js';
import { rowAfterDelete } from '../../state/journeysFormat.js';
import { useJourneyDelete } from '../../state/useJourneyDelete.js';
import { useJourneys } from '../../state/useJourneys.js';
import JourneyImport from './JourneyImport.jsx';
import { deleteButtonId } from './JourneyDelete.jsx';
import JourneyList from './JourneyList.jsx';
import SectionTitle from './SectionTitle.jsx';
import { useFallbackFocus } from './useFallbackFocus.js';

/**
 * Train journeys, opened from Playback's card: the import of a logger's file, and the journeys stored, each with its
 * "Delete…". One confirmation is open at a time. A delete that is done (or that finds the journey already gone) says
 * so in a toast and reads the list again; when the rows have arrived, focus goes to the journey that followed the one
 * deleted, else the one before it, else (no row left) Admin's fallback takes it to the page's title.
 */
export default function AdminJourneys() {
  const { journeys, loading, error, reload } = useJourneys();
  const { showToast, armFocusFallback, takeFocusFallback } = useAdminContext();
  const writer = useJourneyDelete();
  const [openId, setOpenId] = useState(null);
  // The delete that has been answered and whose list is being read again: the id and the list as it was.
  const pending = useRef(null);
  // The latest list read, for a delete that is answered after the list has changed.
  const latest = useRef(journeys);
  latest.current = journeys;

  const open = (id) => {
    writer.clearFailure();
    setOpenId(id);
  };
  const keep = () => {
    writer.clearFailure();
    setOpenId(null);
  };

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
    showToast(outcome.ok ? deleteCopy.toast : deleteCopy.goneToast);
    // No other confirmation can have been opened meanwhile (a press does nothing while a delete runs): this one closes.
    setOpenId(null);
    // The list as it is now, not as it was at the press: an import may have been read meanwhile, and it may have spent
    // the fallback armed above (it found focus on "Deleting…"), so it is armed again.
    pending.current = { id, list: latest.current };
    armFocusFallback();
    reload();
  };

  // The list has been read again after a delete: focus the next journey's "Delete…", else the previous one's. Focus the
  // owner has put somewhere real since stays. When the read failed the heading takes it (below). With no row left, `useFallbackFocus` (armed in `remove`) takes it to the title.
  useEffect(() => {
    const p = pending.current;
    if (!p || loading || (journeys === p.list && !error)) return;
    pending.current = null;
    if (error) {
      // The banner has replaced the rows, so focus that fell with them goes to the card's heading: "Check again" is the
      // next stop. Focus the owner has put somewhere real stays.
      if (document.activeElement === document.body) {
        document.getElementById('admin-journey-list-title').focus();
      }
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
          openId,
          busy: writer.busy,
          failure: writer.failure,
          onOpen: open,
          onKeep: keep,
          onConfirm: remove,
        }}
      />
    </>
  );
}
