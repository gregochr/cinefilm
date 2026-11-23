import { ButtonLink } from '../../components/Button.jsx';
import { JOURNEYS_PATH } from '../../state/adminRoutes.js';
import { journeysCard } from '../../state/journeysCopy.js';

/** Playback's "Train journeys" card: one sentence and the way in to the page. It reads nothing from the server. */
export default function JourneysCard() {
  return (
    <section className="admin__card" aria-labelledby="admin-journeys-title">
      <div className="admin__head">
        <h2 id="admin-journeys-title" className="admin__title">
          {journeysCard.title}
        </h2>
      </div>
      <p className="admin__text">{journeysCard.text}</p>
      <div className="admin__actions admin__actions--wrap">
        <ButtonLink to={JOURNEYS_PATH}>{journeysCard.open}</ButtonLink>
      </div>
    </section>
  );
}
