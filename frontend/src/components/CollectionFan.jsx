import { imageUrl } from '../api/images.js';
import { fanPosters } from '../state/collections.js';
import { placeholderColor } from './placeholder.js';

const STEP = 0.4; // each poster shows this fraction of its width beside the one in front

/**
 * A fan of up to three posters, earliest release at the front and the others stepping back and to the
 * left. Decorative: the card or tile carrying it names the collection. Posters are never see-through;
 * the ones behind are dimmed with a brightness filter.
 */
export default function CollectionFan({ posters, seed, name }) {
  const slots = fanPosters(posters);
  const count = slots.length;
  // Rendered rear to front so the front poster paints last.
  return (
    <span className="fan" aria-hidden="true">
      <span className="fan__stack">
        {[...slots].reverse().map((slot) => {
          const url = imageUrl(slot.path);
          const style = {
            '--depth': slot.depth,
            '--shift': ((count - 1) / 2 - slot.depth) * STEP,
            ...(slot.brightness < 1 ? { filter: `brightness(${slot.brightness})` } : {}),
            ...(url ? {} : { background: placeholderColor(`${seed}-${slot.depth}`, name) }),
          };
          return (
            <span
              key={slot.depth}
              className={`fan__poster${slot.shadow ? ' fan__poster--shadow' : ''}`}
              style={style}
            >
              {url ? (
                <img className="fan__img" src={url} alt="" loading="lazy" decoding="async" />
              ) : null}
            </span>
          );
        })}
      </span>
    </span>
  );
}
