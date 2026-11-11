import { useEffect } from 'react';

export function isTypingTarget(target) {
  if (!target || !target.tagName) return false;
  const tag = target.tagName.toLowerCase();
  return (
    tag === 'input' || tag === 'textarea' || tag === 'select' || Boolean(target.isContentEditable)
  );
}

/** Pressing "/" focuses the page's search field (marked data-global-search) when not typing. */
export function useSlashFocus() {
  useEffect(() => {
    const onKeyDown = (event) => {
      if (event.key !== '/' || event.ctrlKey || event.metaKey || event.altKey) return;
      if (isTypingTarget(event.target)) return;
      // A modal dialog owns the keyboard: nothing behind it may take focus.
      if (event.target.closest?.('[aria-modal="true"]')) return;
      const field = document.querySelector('[data-global-search]');
      if (!field) return;
      event.preventDefault();
      field.focus();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);
}
