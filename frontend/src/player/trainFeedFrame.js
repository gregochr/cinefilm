// One answer from the train's own Wi-Fi system, asked by a hidden, sandboxed frame like the operator lookup's
// (`operator.js`): the request carries no origin of ours, no referrer and no cookie. The answer is returned as raw
// text and is untrusted; nothing here parses it (`trainFeed.js` does). Asking can never break playback: nothing
// here rejects or throws. The page accepts a message only from that frame's own window AND from an opaque origin.
// The feed's address is a parameter: the caller supplies it.

export const FEED_TIMEOUT_MS = 8000;
/** The most characters of an answer the frame posts and the page accepts: a bigger one is no answer. */
export const MAX_FEED_CHARS = 65536;
/** The `origin` of a message from a sandboxed frame without allow-same-origin: its origin is opaque. */
export const SANDBOXED_ORIGIN = 'null';
export const FRAME_TEST_ID = 'train-feed-frame';

/** A string safe inside a `<script>`: JSON with `<` escaped, so no address can close the script or open a comment. */
const inScript = (value) => JSON.stringify(value).replaceAll('<', '\\u003c');

/**
 * The frame's whole document for one address. A CORS `fetch` (not `no-cors`: the browser enforces the feed's
 * `Access-Control-Allow-Origin`, and an answer without it rejects with a TypeError, which posts null).
 */
export function frameHtml(url) {
  return `<!doctype html><meta name="referrer" content="no-referrer"><script>
fetch(${inScript(url)}, { credentials: "omit", referrerPolicy: "no-referrer", cache: "no-store" })
  .then(function (r) { return r.ok ? r.text() : null; })
  .then(function (t) { parent.postMessage({ cinefilmFeed: typeof t === "string" && t.length <= ${MAX_FEED_CHARS} ? t : null }, "*"); })
  .catch(function () { parent.postMessage({ cinefilmFeed: null }, "*"); });
</script>`;
}

let inFlight = false;

/**
 * Resolves the feed's answer as text, or null: when the fetch fails (no CORS header, a network error), the answer
 * is too big, it times out after 8 s, `signal` aborts, or another ask is already in flight (one at a time, and the
 * second gets null at once). Never rejects. Ending removes the frame, the `message` listener, the timer and the
 * abort listener.
 */
export function askTrainFeed(
  url,
  { signal, doc = globalThis.document, win = globalThis.window } = {},
) {
  return new Promise((resolve) => {
    if (inFlight || signal?.aborted || typeof url !== 'string' || url === '') {
      resolve(null);
      return;
    }
    inFlight = true;
    let frame = null;
    let timer = null;
    const end = (value) => {
      inFlight = false; // first: nothing below may leave the slot taken
      resolve(value);
      clearTimeout(timer);
      // Each undo on its own: one that throws must not stop the others (a frame left in the page, say).
      for (const undo of [
        () => win.removeEventListener('message', onMessage),
        () => signal.removeEventListener('abort', onAbort),
        () => frame.remove(), // no frame yet throws here, and there is nothing to remove
      ]) {
        try {
          undo();
        } catch {
          // Nothing more to undo.
        }
      }
    };
    const onMessage = (event) => {
      // From the frame itself, and sandboxed: a frame that kept our origin has lost its sandbox, so fail closed.
      if (event.source !== frame.contentWindow || event.origin !== SANDBOXED_ORIGIN) return;
      const data = Object(event.data); // null, a string or a number have no such key
      if (!Object.hasOwn(data, 'cinefilmFeed')) return;
      const text = data.cinefilmFeed;
      end(typeof text === 'string' && text.length <= MAX_FEED_CHARS ? text : null);
    };
    const onAbort = () => end(null);
    try {
      signal?.addEventListener('abort', onAbort);
      timer = setTimeout(() => end(null), FEED_TIMEOUT_MS);
      win.addEventListener('message', onMessage);
      frame = doc.createElement('iframe');
      // The sandbox comes before srcdoc: a browser sandboxes only a document loaded after the attribute is set.
      frame.setAttribute('sandbox', 'allow-scripts');
      frame.hidden = true;
      frame.setAttribute('aria-hidden', 'true');
      frame.tabIndex = -1;
      frame.setAttribute('data-testid', FRAME_TEST_ID);
      frame.srcdoc = frameHtml(url);
      doc.body.appendChild(frame);
    } catch {
      end(null);
    }
  });
}
