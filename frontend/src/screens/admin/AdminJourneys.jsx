import { Link } from 'react-router-dom';
import { journeysPage } from '../../state/journeysCopy.js';
import JourneyImport from './JourneyImport.jsx';
import SectionTitle from './SectionTitle.jsx';

/** Train journeys, opened from Playback's card: the import of a logger's file (the list of stored journeys follows). */
export default function AdminJourneys() {
  return (
    <>
      <Link to="/admin/playback" className="admin__back">
        {journeysPage.back}
      </Link>
      <SectionTitle>{journeysPage.title}</SectionTitle>
      <JourneyImport />
    </>
  );
}
