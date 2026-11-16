import { Button } from './Button.jsx';

/**
 * The one failure line of an Admin card or list: an inline banner in the error colours (never amber, no heading of its
 * own: the card's heading already names where it is) with an optional retry. It is a `role="alert"`, so a new banner
 * is announced. `announceKey` re-creates the text when it changes, so a second failure with the same words is announced
 * again while the retry button, which is not re-created, keeps focus. While `busy` the retry is `aria-disabled` (it keeps focus) and a press does nothing.
 */
export default function AdminErrorBanner({
  children,
  onRetry,
  busy = false,
  announceKey,
  retryLabel = 'Try again',
}) {
  return (
    <div className="admin__todo-error" role="alert">
      <p key={announceKey} className="admin__todo-error-text">
        {children}
      </p>
      {onRetry ? (
        <Button
          aria-busy={busy || undefined}
          aria-disabled={busy || undefined}
          onClick={busy ? undefined : onRetry}
        >
          {retryLabel}
        </Button>
      ) : null}
    </div>
  );
}
