import { useId } from 'react';
import { Button } from './Button.jsx';

/**
 * A button with its hint line under it: a visible `<p>`, never a tooltip, so it works on the phone. The button is
 * described by the hint (`aria-describedby`). `layout` is "row" (right-aligned, for a row of a list) or "line"
 * (left-aligned, for a card's own action). The wrapper's `action-hint--row|line` class carries the alignment. The
 * wrapper has no role or text to query (test-standards 'Queries'), so only the hint's own
 * `action-hint__text--row|line` class is tested. At 600px and below the button is full width, 44px tall (at least; a
 * To do row grows past that when its label wraps), hint on the left.
 * `describedBy` adds more ids to the description (a row's title, so a button names its film without changing its label).
 * `buttonClassName` is an extra class on the button itself (`className` is the wrapper's).
 * `variant` is the Button's: the one amber button in Admin is "Scan library now".
 */
export function ActionWithHint({
  hint,
  layout = 'row',
  variant = 'secondary',
  describedBy,
  className,
  buttonClassName,
  children,
  ...rest
}) {
  const hintId = useId();
  const describedByIds = [hint ? hintId : null, describedBy].filter(Boolean).join(' ') || undefined;
  return (
    <div className={['action-hint', `action-hint--${layout}`, className].filter(Boolean).join(' ')}>
      <Button
        variant={variant}
        className={buttonClassName}
        aria-describedby={describedByIds}
        {...rest}
      >
        {children}
      </Button>
      {hint ? (
        <p id={hintId} className={`action-hint__text action-hint__text--${layout}`}>
          {hint}
        </p>
      ) : null}
    </div>
  );
}
