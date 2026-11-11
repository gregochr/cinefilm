// iOS raises the keyboard only for a focus() made during a user gesture. The sheet's own input does not exist yet
// when the Search tab is tapped, so the tab focuses a throwaway input synchronously; the sheet then moves focus to
// its real input (the keyboard stays up) and removes this one.
let proxy = null;

export function raiseKeyboard() {
  releaseKeyboardProxy();
  const el = document.createElement('input');
  el.setAttribute('aria-hidden', 'true');
  el.tabIndex = -1;
  el.style.cssText =
    'position:fixed;top:0;left:0;width:1px;height:1px;opacity:0;border:0;padding:0;font-size:16px;';
  document.body.appendChild(el);
  el.focus({ preventScroll: true });
  proxy = el;
}

export function releaseKeyboardProxy() {
  proxy?.remove();
  proxy = null;
}
