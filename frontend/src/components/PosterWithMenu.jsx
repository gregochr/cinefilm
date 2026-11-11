import ContinueMenu from './ContinueMenu.jsx';
import { MoreIcon } from './icons.jsx';
import { Poster } from './Poster.jsx';
import { useCardMenu } from './useCardMenu.js';

function MenuPoster({ film, onRemove, onMarkWatched, linkRef }) {
  const canRemove = !film.hiddenFromContinueWatching;
  const { menu, moreRef, close, wrapper, button } = useCardMenu({
    film,
    canRemove,
    onRemove,
    onMarkWatched,
  });
  return (
    // The wrapper only forwards keys and touches that start on the link or the ⋯ button.
    <div className="continue-card poster-cell" {...wrapper}>
      <Poster film={film} linkRef={linkRef} />
      <button type="button" className="continue-card__more" {...button}>
        <MoreIcon size={20} />
      </button>
      {menu ? (
        <ContinueMenu
          film={film}
          sheet={menu === 'sheet'}
          anchorRef={moreRef}
          onClose={close}
          canRemove={canRemove}
          onRemove={() => onRemove(film)}
          onMarkWatched={() => onMarkWatched(film)}
        />
      ) : null}
    </div>
  );
}

/**
 * A poster in the Library grid. One that is in progress gets the Continue watching ⋯ menu (Mark as watched, Start from
 * beginning and, unless the film is already out of Continue watching, Remove) and behaves like a Continue watching
 * card: Delete/Backspace removes and M marks watched while it has focus, and a long press opens the sheet on touch.
 * The ⋯ button is a sibling of the poster link, never inside it. Any other film is a plain poster.
 */
export function PosterWithMenu({ film, onRemove, onMarkWatched, linkRef }) {
  if (!film.inProgress) return <Poster film={film} linkRef={linkRef} />;
  return (
    <MenuPoster film={film} onRemove={onRemove} onMarkWatched={onMarkWatched} linkRef={linkRef} />
  );
}
