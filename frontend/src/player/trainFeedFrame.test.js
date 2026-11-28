import { screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  FEED_TIMEOUT_MS,
  FRAME_TEST_ID,
  MAX_FEED_CHARS,
  askTrainFeed,
  frameHtml,
} from './trainFeedFrame.js';

// A made-up address: never a real one.
const URL_A = 'https://feed.example.test/api/position';

beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  for (const frame of screen.queryAllByTestId(FRAME_TEST_ID)) frame.remove();
});

const frameNow = () => screen.getByTestId(FRAME_TEST_ID);
const post = (data, { origin = 'null', source = frameNow().contentWindow } = {}) =>
  window.dispatchEvent(new MessageEvent('message', { data, origin, source }));
const answer = (text) => post({ cinefilmFeed: text });
const settled = (promise) => {
  const state = { done: false };
  promise.then(() => {
    state.done = true;
  });
  return state;
};

describe('the frame', () => {
  it('is hidden, sandboxed to scripts only, never same-origin, and carries the document', async () => {
    const promise = askTrainFeed(URL_A);
    const frame = frameNow();
    expect(frame.tagName).toBe('IFRAME');
    expect(frame.getAttribute('sandbox')).toBe('allow-scripts');
    expect(frame.getAttribute('sandbox')).not.toContain('allow-same-origin');
    expect(frame.hidden).toBe(true);
    expect(frame).toHaveAttribute('aria-hidden', 'true');
    expect(frame.tabIndex).toBe(-1);
    expect(frame.srcdoc).toBe(frameHtml(URL_A));
    expect(document.body).toContainElement(frame);
    answer('x');
    await promise;
  });

  it('sets the sandbox before the source and only then adds the frame to the page', async () => {
    const log = [];
    const frame = {
      setAttribute: (name, value) => log.push(`attribute ${name}=${value}`),
      set hidden(value) {
        log.push(`hidden=${value}`);
      },
      set tabIndex(value) {
        log.push(`tabIndex=${value}`);
      },
      set srcdoc(value) {
        log.push(`srcdoc ${value === frameHtml(URL_A) ? 'is the frame document' : 'is wrong'}`);
      },
      contentWindow: {},
      remove: () => log.push('removed'),
    };
    const doc = {
      createElement: (tag) => {
        log.push(`create ${tag}`);
        return frame;
      },
      body: { appendChild: () => log.push('append') },
    };
    const controller = new AbortController();
    const promise = askTrainFeed(URL_A, { doc, signal: controller.signal });
    expect(log).toEqual([
      'create iframe',
      'attribute sandbox=allow-scripts',
      'hidden=true',
      'attribute aria-hidden=true',
      'tabIndex=-1',
      `attribute data-testid=${FRAME_TEST_ID}`,
      'srcdoc is the frame document',
      'append',
    ]);
    controller.abort();
    expect(await promise).toBeNull();
  });

  it('asks the address with a CORS fetch, no credentials and no referrer', () => {
    const html = frameHtml(URL_A);
    expect(html).toContain(`fetch(${JSON.stringify(URL_A)}`);
    expect(html).toContain('credentials: "omit"');
    expect(html).toContain('referrerPolicy: "no-referrer"');
    expect(html).not.toContain('no-cors');
    expect(html).toContain('<meta name="referrer" content="no-referrer">');
    expect(html.startsWith('<!doctype html>')).toBe(true);
    expect(html.endsWith('</script>')).toBe(true);
    expect(html).toContain(`t.length <= ${MAX_FEED_CHARS}`);
    expect(html).toContain(
      '.catch(function () { parent.postMessage({ cinefilmFeed: null }, "*"); })',
    );
  });

  it('cannot be broken out of by an address with markup in it', () => {
    const html = frameHtml('https://feed.example.test/</script><script>alert(1)</script>');
    expect(html.match(/<\/script>/g)).toHaveLength(1);
    expect(html).toContain('\\u003c/script>');
  });
});

describe('the answer', () => {
  it('is the text the frame posts, untouched', async () => {
    const promise = askTrainFeed(URL_A);
    answer(' {"a": 1}\n');
    expect(await promise).toBe(' {"a": 1}\n');
  });

  it('is null when the fetch rejected (a missing CORS header is a TypeError) and the frame posted null', async () => {
    const promise = askTrainFeed(URL_A);
    answer(null);
    expect(await promise).toBeNull();
  });

  it('ignores a message from another window and waits for the frame', async () => {
    const promise = askTrainFeed(URL_A);
    const state = settled(promise);
    post({ cinefilmFeed: 'impostor' }, { source: window });
    post({ cinefilmFeed: 'impostor' }, { source: null });
    await vi.advanceTimersByTimeAsync(0);
    expect(state.done).toBe(false);
    expect(frameNow()).toBeInTheDocument();
    answer('real');
    expect(await promise).toBe('real');
  });

  it('ignores a message from the frame whose origin is not null, and a later good one lands', async () => {
    const promise = askTrainFeed(URL_A);
    const state = settled(promise);
    for (const origin of ['https://cinefilm.example', '']) {
      post({ cinefilmFeed: 'unsandboxed' }, { origin });
    }
    await vi.advanceTimersByTimeAsync(0);
    expect(state.done).toBe(false);
    answer('sandboxed');
    expect(await promise).toBe('sandboxed');
  });

  it('ignores a message without the key, and a later good one lands', async () => {
    const promise = askTrainFeed(URL_A);
    const state = settled(promise);
    post({ somethingElse: 'x' });
    post('x');
    post(null);
    await vi.advanceTimersByTimeAsync(0);
    expect(state.done).toBe(false);
    answer('good');
    expect(await promise).toBe('good');
  });

  it.each([
    ['a number', 42],
    ['an object', { a: 1 }],
    ['undefined', undefined],
    ['too long', 'x'.repeat(MAX_FEED_CHARS + 1)],
  ])('is null for %s', async (_name, value) => {
    const promise = askTrainFeed(URL_A);
    answer(value);
    expect(await promise).toBeNull();
  });

  it('keeps an answer of exactly the cap and an empty one', async () => {
    const text = 'y'.repeat(MAX_FEED_CHARS);
    let promise = askTrainFeed(URL_A);
    answer(text);
    expect(await promise).toBe(text);
    promise = askTrainFeed(URL_A);
    answer('');
    expect(await promise).toBe('');
  });

  it('answers once: a second message after the first does nothing', async () => {
    const promise = askTrainFeed(URL_A);
    const source = frameNow().contentWindow;
    answer('first');
    post({ cinefilmFeed: 'second' }, { source });
    expect(await promise).toBe('first');
  });
});

describe('one at a time', () => {
  it('gives a second ask null at once, with no second frame, and leaves the first alone', async () => {
    const first = askTrainFeed(URL_A);
    expect(await askTrainFeed(URL_A)).toBeNull();
    expect(screen.getAllByTestId(FRAME_TEST_ID)).toHaveLength(1);
    answer('one');
    expect(await first).toBe('one');
  });

  it('allows a new ask once the last has ended', async () => {
    let promise = askTrainFeed(URL_A);
    await vi.advanceTimersByTimeAsync(FEED_TIMEOUT_MS);
    expect(await promise).toBeNull();
    promise = askTrainFeed(URL_A);
    answer('again');
    expect(await promise).toBe('again');
  });

  it.each([
    ['empty', ''],
    ['not text', 7],
  ])('is null with no frame for an address that is %s', async (_name, url) => {
    expect(await askTrainFeed(url)).toBeNull();
    expect(screen.queryByTestId(FRAME_TEST_ID)).not.toBeInTheDocument();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('is null with no frame when already aborted', async () => {
    expect(await askTrainFeed(URL_A, { signal: AbortSignal.abort() })).toBeNull();
    expect(screen.queryByTestId(FRAME_TEST_ID)).not.toBeInTheDocument();
    expect(vi.getTimerCount()).toBe(0);
  });
});

describe('the end of an ask', () => {
  it('gives up at 8 s (not before), and removes the frame', async () => {
    const promise = askTrainFeed(URL_A);
    const state = settled(promise);
    await vi.advanceTimersByTimeAsync(FEED_TIMEOUT_MS - 1);
    expect(state.done).toBe(false);
    expect(frameNow()).toBeInTheDocument();
    await vi.advanceTimersByTimeAsync(1);
    expect(await promise).toBeNull();
    expect(FEED_TIMEOUT_MS).toBe(8000);
    expect(screen.queryByTestId(FRAME_TEST_ID)).not.toBeInTheDocument();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('removes the frame, the listener, the timer and the abort listener on an answer', async () => {
    const removeMessage = vi.spyOn(window, 'removeEventListener');
    const controller = new AbortController();
    const removeAbort = vi.spyOn(controller.signal, 'removeEventListener');
    const promise = askTrainFeed(URL_A, { signal: controller.signal });
    answer('done');
    await promise;
    expect(screen.queryByTestId(FRAME_TEST_ID)).not.toBeInTheDocument();
    expect(removeMessage).toHaveBeenCalledWith('message', expect.any(Function));
    expect(removeAbort).toHaveBeenCalledWith('abort', expect.any(Function));
    expect(vi.getTimerCount()).toBe(0);
  });

  it('stops at once when the caller aborts, cleans up, and ignores a late message', async () => {
    const controller = new AbortController();
    const promise = askTrainFeed(URL_A, { signal: controller.signal });
    const source = frameNow().contentWindow;
    controller.abort();
    expect(await promise).toBeNull();
    expect(screen.queryByTestId(FRAME_TEST_ID)).not.toBeInTheDocument();
    expect(vi.getTimerCount()).toBe(0);
    post({ cinefilmFeed: 'late' }, { source });
    expect(await promise).toBeNull();
  });
});

describe('where it cannot start', () => {
  it('is null, never throws, and frees the slot when the page has no body', async () => {
    const doc = { createElement: (tag) => document.createElement(tag), body: null };
    expect(await askTrainFeed(URL_A, { doc })).toBeNull();
    expect(vi.getTimerCount()).toBe(0);
    const promise = askTrainFeed(URL_A);
    answer('after');
    expect(await promise).toBe('after');
  });

  it('is null when the page cannot make a frame', async () => {
    const doc = {
      createElement: () => {
        throw new Error('no frames here');
      },
      body: document.body,
    };
    expect(await askTrainFeed(URL_A, { doc })).toBeNull();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('is null when there is no window to listen on', async () => {
    expect(await askTrainFeed(URL_A, { win: null })).toBeNull();
    expect(vi.getTimerCount()).toBe(0);
    expect(screen.queryByTestId(FRAME_TEST_ID)).not.toBeInTheDocument();
  });
});
