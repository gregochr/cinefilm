import { Link } from 'react-router-dom';
import { journeysPage } from '../../state/journeysCopy.js';
import { useJourneys } from '../../state/useJourneys.js';
import JourneyImport from './JourneyImport.jsx';
import JourneyList from './JourneyList.jsx';
import SectionTitle from './SectionTitle.jsx';

/** Train journeys, opened from Playback's card: the import of a logger's file, and the journeys stored. */
export default function AdminJourneys() {
  const { journeys, loading, error, reload } = useJourneys();
  return (
    <>
      <Link to="/admin/playback" className="admin__back">
        {journeysPage.back}
      </Link>
      <SectionTitle>{journeysPage.title}</SectionTitle>
      <JourneyImport onImported={reload} />
      <JourneyList journeys={journeys} loading={loading} error={error} onRetry={reload} />
    </>
  );
}
