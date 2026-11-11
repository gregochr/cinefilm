// The network operator's name, looked up by a hidden, sandboxed frame so the request carries no origin of
// ours, no referrer and no cookie. Nothing but the `org` string leaves this module, and it is untrusted text:
// it is only ever rendered as text. Detection can never break playback: nothing here rejects or throws.

export const LOOKUP_URL = 'https://ipinfo.io/json';
export const LOOKUP_TIMEOUT_MS = 15000;
export const MAX_OPERATOR_LENGTH = 100;
/** What the dev fixture server reports: no frame, no request. */
export const FIXTURE_OPERATOR = 'AS64496 Example Rail Wi-Fi';

// The frame's whole document, as verified in a real browser. It is a JS string, so the closing tag is harmless.
export const FRAME_HTML = `<!doctype html><meta name="referrer" content="no-referrer"><script>
fetch(${JSON.stringify(LOOKUP_URL)}, { credentials: "omit", referrerPolicy: "no-referrer", cache: "no-store", headers: { Accept: "application/json" } })
  .then(function (r) { return r.ok ? r.json() : null; })
  .then(function (d) { parent.postMessage({ cinefilmOperator: d && typeof d.org === "string" ? d.org : null }, "*"); })
  .catch(function () { parent.postMessage({ cinefilmOperator: null }, "*"); });
</script>`;

/** The trimmed name when it is a string of 1 to 100 characters, else null. */
export function cleanOperator(value) {
  if (typeof value !== 'string') return null;
  const name = value.trim();
  return name.length >= 1 && name.length <= MAX_OPERATOR_LENGTH ? name : null;
}

/**
 * Resolves the operator's `org` (cleaned) or null: when the lookup fails, times out after 15 s, or `signal`
 * aborts. Never rejects. Ending removes the frame, the `message` listener, the timer and the abort listener.
 */
export function lookupOperator({
  signal,
  doc = globalThis.document,
  win = globalThis.window,
} = {}) {
  if (import.meta.env.VITE_FIXTURES === '1') return Promise.resolve(FIXTURE_OPERATOR);
  return new Promise((resolve) => {
    if (signal?.aborted) {
      resolve(null);
      return;
    }
    let frame = null;
    let timer = null;
    // Ending twice is harmless: the promise settles once and every removal is idempotent.
    const end = (value) => {
      clearTimeout(timer);
      win?.removeEventListener('message', onMessage);
      signal?.removeEventListener('abort', onAbort);
      try {
        frame.remove(); // no frame yet throws here, and there is nothing to remove
      } catch {
        // Nothing to remove.
      }
      resolve(value);
    };
    const onMessage = (event) => {
      if (event.source !== frame.contentWindow) return;
      const data = Object(event.data); // null, a string or a number have no such key
      if (!Object.hasOwn(data, 'cinefilmOperator')) return;
      end(cleanOperator(data.cinefilmOperator));
    };
    const onAbort = () => end(null);
    try {
      signal?.addEventListener('abort', onAbort);
      timer = setTimeout(() => end(null), LOOKUP_TIMEOUT_MS);
      win.addEventListener('message', onMessage);
      frame = doc.createElement('iframe');
      // The sandbox comes before srcdoc: a browser sandboxes only a document loaded after the attribute is set.
      frame.setAttribute('sandbox', 'allow-scripts');
      frame.hidden = true;
      frame.setAttribute('aria-hidden', 'true');
      frame.tabIndex = -1;
      frame.setAttribute('data-testid', 'operator-lookup');
      frame.srcdoc = FRAME_HTML;
      doc.body.appendChild(frame);
    } catch {
      end(null);
    }
  });
}
