import { screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  FIXTURE_OPERATOR,
  FRAME_HTML,
  LOOKUP_TIMEOUT_MS,
  LOOKUP_URL,
  MAX_OPERATOR_LENGTH,
  cleanOperator,
  lookupOperator,
} from './operator.js';

beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
  for (const frame of screen.queryAllByTestId('operator-lookup')) frame.remove();
});

const frameNow = () => screen.getByTestId('operator-lookup');
// What the frame's own script would post, with the frame's window as the source.
const fromFrame = (data) =>
  window.dispatchEvent(new MessageEvent('message', { data, source: frameNow().contentWindow }));
const org = (value) => fromFrame({ cinefilmOperator: value });
const settled = (promise) => {
  const state = { done: false };
  promise.then(() => {
    state.done = true;
  });
  return state;
};

describe('the lookup frame', () => {
  it('is hidden, sandboxed to scripts only, inert for the keyboard and for screen readers', async () => {
    const promise = lookupOperator();
    const frame = frameNow();
    expect(frame.tagName).toBe('IFRAME');
    // jsdom has no `sandbox` property on an iframe: read the attribute.
    expect(frame.getAttribute('sandbox')).toBe('allow-scripts');
    expect(frame.hidden).toBe(true);
    expect(frame).toHaveAttribute('aria-hidden', 'true');
    expect(frame.tabIndex).toBe(-1);
    expect(frame.srcdoc).toBe(FRAME_HTML);
    expect(document.body).toContainElement(frame);
    org('AS1 X');
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
        log.push(`srcdoc ${value === FRAME_HTML ? 'is the frame document' : 'is wrong'}`);
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
    const promise = lookupOperator({ doc, signal: controller.signal });
    expect(log).toEqual([
      'create iframe',
      'attribute sandbox=allow-scripts',
      'hidden=true',
      'attribute aria-hidden=true',
      'tabIndex=-1',
      'attribute data-testid=operator-lookup',
      'srcdoc is the frame document',
      'append',
    ]);
    controller.abort();
    expect(await promise).toBeNull();
  });

  it('carries a document that asks the one address with no credentials and no referrer', () => {
    expect(LOOKUP_URL).toBe('https://ipinfo.io/json');
    expect(FRAME_HTML).toContain(`fetch(${JSON.stringify(LOOKUP_URL)}`);
    expect(FRAME_HTML).toContain('credentials: "omit"');
    expect(FRAME_HTML).toContain('referrerPolicy: "no-referrer"');
    expect(FRAME_HTML).toContain('<meta name="referrer" content="no-referrer">');
    expect(FRAME_HTML.startsWith('<!doctype html>')).toBe(true);
    expect(FRAME_HTML.endsWith('</script>')).toBe(true);
  });
});

describe('the answer', () => {
  it('is the org the frame posts, trimmed', async () => {
    const promise = lookupOperator();
    org('  AS5089 Virgin Media Limited \n');
    expect(await promise).toBe('AS5089 Virgin Media Limited');
  });

  it('is null when the frame could not find one', async () => {
    const promise = lookupOperator();
    org(null);
    expect(await promise).toBeNull();
  });

  it('ignores a message from another window and waits for the frame', async () => {
    const promise = lookupOperator();
    const state = settled(promise);
    window.dispatchEvent(
      new MessageEvent('message', { data: { cinefilmOperator: 'AS1 Impostor' }, source: window }),
    );
    window.dispatchEvent(
      new MessageEvent('message', { data: { cinefilmOperator: 'AS1 Impostor' }, source: null }),
    );
    await vi.advanceTimersByTimeAsync(0);
    expect(state.done).toBe(false);
    expect(screen.getByTestId('operator-lookup')).toBeInTheDocument();
    org('AS2 Real');
    expect(await promise).toBe('AS2 Real');
  });

  it('ignores a message from the frame that does not carry the key, and a later good one lands', async () => {
    const promise = lookupOperator();
    const state = settled(promise);
    fromFrame({ somethingElse: 'AS1 X' });
    fromFrame('AS1 X');
    fromFrame(null);
    fromFrame(['cinefilmOperator']);
    await vi.advanceTimersByTimeAsync(0);
    expect(state.done).toBe(false);
    org('AS3 Good');
    expect(await promise).toBe('AS3 Good');
  });

  it.each([
    ['a number', 42],
    ['an object', { name: 'x' }],
    ['undefined', undefined],
    ['an empty string', ''],
    ['a blank string', '   \t '],
    ['101 characters', 'x'.repeat(MAX_OPERATOR_LENGTH + 1)],
  ])('is null for %s', async (_name, value) => {
    const promise = lookupOperator();
    org(value);
    expect(await promise).toBeNull();
  });

  it('keeps a name of exactly 100 characters', async () => {
    const name = 'y'.repeat(MAX_OPERATOR_LENGTH);
    const promise = lookupOperator();
    org(name);
    expect(await promise).toBe(name);
  });
});

describe('cleanOperator', () => {
  it('trims, and accepts 1 to 100 characters', () => {
    expect(cleanOperator(' a ')).toBe('a');
    expect(cleanOperator('z'.repeat(100))).toBe('z'.repeat(100));
    expect(cleanOperator(` ${'z'.repeat(100)} `)).toBe('z'.repeat(100));
  });

  it('refuses everything else', () => {
    expect(cleanOperator('z'.repeat(101))).toBeNull();
    expect(cleanOperator('')).toBeNull();
    expect(cleanOperator('  ')).toBeNull();
    expect(cleanOperator(null)).toBeNull();
    expect(cleanOperator(undefined)).toBeNull();
    expect(cleanOperator(7)).toBeNull();
  });
});

describe('the end of a lookup', () => {
  it('gives up at 15 s (not at 14.999 s)', async () => {
    const promise = lookupOperator();
    const state = settled(promise);
    await vi.advanceTimersByTimeAsync(LOOKUP_TIMEOUT_MS - 1);
    expect(state.done).toBe(false);
    expect(screen.getByTestId('operator-lookup')).toBeInTheDocument();
    await vi.advanceTimersByTimeAsync(1);
    expect(await promise).toBeNull();
    expect(LOOKUP_TIMEOUT_MS).toBe(15000);
    expect(screen.queryByTestId('operator-lookup')).not.toBeInTheDocument();
  });

  it('removes the frame, the listener, the timer and the abort listener on an answer', async () => {
    const removeMessage = vi.spyOn(window, 'removeEventListener');
    const controller = new AbortController();
    const removeAbort = vi.spyOn(controller.signal, 'removeEventListener');
    const promise = lookupOperator({ signal: controller.signal });
    org('AS1 Done');
    await promise;
    expect(screen.queryByTestId('operator-lookup')).not.toBeInTheDocument();
    expect(removeMessage).toHaveBeenCalledWith('message', expect.any(Function));
    expect(removeAbort).toHaveBeenCalledWith('abort', expect.any(Function));
    expect(vi.getTimerCount()).toBe(0);
  });

  it('removes the frame, the listener and the timer on the timeout', async () => {
    const removeMessage = vi.spyOn(window, 'removeEventListener');
    const promise = lookupOperator();
    await vi.advanceTimersByTimeAsync(LOOKUP_TIMEOUT_MS);
    await promise;
    expect(screen.queryByTestId('operator-lookup')).not.toBeInTheDocument();
    expect(removeMessage).toHaveBeenCalledWith('message', expect.any(Function));
    expect(vi.getTimerCount()).toBe(0);
  });

  it('stops at once when the caller aborts, and cleans up', async () => {
    const removeMessage = vi.spyOn(window, 'removeEventListener');
    const controller = new AbortController();
    const promise = lookupOperator({ signal: controller.signal });
    expect(screen.getByTestId('operator-lookup')).toBeInTheDocument();
    const frame = frameNow();
    controller.abort();
    expect(await promise).toBeNull();
    expect(screen.queryByTestId('operator-lookup')).not.toBeInTheDocument();
    expect(removeMessage).toHaveBeenCalledWith('message', expect.any(Function));
    expect(vi.getTimerCount()).toBe(0);
    // A message arriving after the end does nothing.
    window.dispatchEvent(
      new MessageEvent('message', {
        data: { cinefilmOperator: 'AS1 Late' },
        source: frame.contentWindow,
      }),
    );
    expect(await promise).toBeNull();
  });

  it('makes no frame and starts no timer when already aborted', async () => {
    const controller = new AbortController();
    controller.abort();
    expect(await lookupOperator({ signal: controller.signal })).toBeNull();
    expect(screen.queryByTestId('operator-lookup')).not.toBeInTheDocument();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('answers once: a second message after the first does nothing', async () => {
    const promise = lookupOperator();
    const frame = frameNow();
    org('AS1 First');
    window.dispatchEvent(
      new MessageEvent('message', {
        data: { cinefilmOperator: 'AS2 Second' },
        source: frame.contentWindow,
      }),
    );
    expect(await promise).toBe('AS1 First');
  });
});

describe('where it cannot start', () => {
  it('is null when the page has no body', async () => {
    const removeMessage = vi.spyOn(window, 'removeEventListener');
    const doc = { createElement: (tag) => document.createElement(tag), body: null };
    let promise;
    expect(() => {
      promise = lookupOperator({ doc });
    }).not.toThrow();
    expect(await promise).toBeNull();
    expect(removeMessage).toHaveBeenCalledWith('message', expect.any(Function));
    expect(vi.getTimerCount()).toBe(0);
  });

  it('is null, and never throws, when the page cannot make a frame', async () => {
    const doc = {
      createElement: () => {
        throw new Error('no frames here');
      },
      body: document.body,
    };
    let promise;
    expect(() => {
      promise = lookupOperator({ doc });
    }).not.toThrow();
    expect(await promise).toBeNull();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('is null when there is no window to listen on', async () => {
    let promise;
    expect(() => {
      promise = lookupOperator({ win: null });
    }).not.toThrow();
    expect(await promise).toBeNull();
    expect(vi.getTimerCount()).toBe(0);
    expect(screen.queryByTestId('operator-lookup')).not.toBeInTheDocument();
  });

  it('answers the fixture name with no frame in fixture mode', async () => {
    vi.stubEnv('VITE_FIXTURES', '1');
    expect(await lookupOperator()).toBe(FIXTURE_OPERATOR);
    expect(FIXTURE_OPERATOR).toBe('AS64496 Example Rail Wi-Fi');
    expect(screen.queryByTestId('operator-lookup')).not.toBeInTheDocument();
    expect(vi.getTimerCount()).toBe(0);
  });
});
