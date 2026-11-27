// A stand-in for hls.js at its event API: tests emit the events hls.js would and read what the player asked it.
import { vi } from 'vitest';

export class FakeHls {
  static instances = [];
  static supported = true;
  static Events = {
    MANIFEST_PARSED: 'hlsManifestParsed',
    LEVEL_SWITCHING: 'hlsLevelSwitching',
    LEVEL_SWITCHED: 'hlsLevelSwitched',
    FRAG_LOADING: 'hlsFragLoading',
    FRAG_LOAD_EMERGENCY_ABORTED: 'hlsFragLoadEmergencyAborted',
    FRAG_LOADED: 'hlsFragLoaded',
    FRAG_BUFFERED: 'hlsFragBuffered',
    BUFFER_FLUSHING: 'hlsBufferFlushing',
    BUFFER_FLUSHED: 'hlsBufferFlushed',
    ERROR: 'hlsError',
  };
  static ErrorTypes = {
    NETWORK_ERROR: 'networkError',
    MEDIA_ERROR: 'mediaError',
    OTHER_ERROR: 'otherError',
  };
  // hls.js 1.7.4's real strings (`src/errors.ts`) for the details the engine reads.
  static ErrorDetails = {
    MANIFEST_LOAD_ERROR: 'manifestLoadError',
    MANIFEST_LOAD_TIMEOUT: 'manifestLoadTimeOut',
    MANIFEST_PARSING_ERROR: 'manifestParsingError',
    LEVEL_EMPTY_ERROR: 'levelEmptyError',
    LEVEL_LOAD_ERROR: 'levelLoadError',
    LEVEL_LOAD_TIMEOUT: 'levelLoadTimeOut',
    LEVEL_PARSING_ERROR: 'levelParsingError',
    FRAG_LOAD_ERROR: 'fragLoadError',
    FRAG_LOAD_TIMEOUT: 'fragLoadTimeOut',
    INTERNAL_ABORTED: 'aborted',
    MEDIA_SOURCE_REQUIRES_RESET: 'mediaSourceRequiresReset',
  };
  // The loader hls.js uses when no `fLoader` is set: a stub class, so tests can tell it from the wrapper.
  static DefaultConfig = {
    loader: class DefaultLoader {},
  };
  static isSupported() {
    return FakeHls.supported;
  }
  static reset() {
    FakeHls.instances = [];
    FakeHls.supported = true;
  }
  static get last() {
    return FakeHls.instances[FakeHls.instances.length - 1];
  }

  constructor(config) {
    this.config = { minAutoBitrate: 0, maxBufferHole: 0.1, ...config }; // hls.js's default hole
    this.currentLevel = -1; // the level last switched to, by the engine's `loadLevel` or by Auto
    this.handlers = {};
    this.levels = [];
    this.autoLevelCapping = -1;
    this.loadSource = vi.fn();
    this.video = null;
    this.startPosition = -1; // hls.js's `startPosition`: what `startLoad` was last given
    this.attachMedia = vi.fn((video) => {
      this.video = video;
    });
    this.started = true; // loading runs from the start (`autoStartLoad`) until `stopLoad`
    this.startLoad = vi.fn((position = -1) => {
      this.started = true;
      this.startPosition = position;
    });
    this.stopLoad = vi.fn(() => {
      this.started = false;
    });
    // hls.ts `recoverMediaError`: reads the playhead and whether loading had started, detaches (which stops
    // loading) and attaches, and restarts loading from the time it read when it had started and the time is not 0.
    this.recoverMediaError = vi.fn(() => {
      const time = this.video?.currentTime;
      const { started } = this;
      this.stopLoad();
      if (started && time) this.startLoad(time);
    });
    this.destroy = vi.fn();
    this.trigger = vi.fn(); // what the engine asks hls.js to do (the buffer flush)
    FakeHls.instances.push(this);
  }

  // hls.js 1.7.4 (`level-controller.ts`): the level last switched to; setting a level other than -1 that is not
  // the current one fires LEVEL_SWITCHING at once. A manual level is only remembered here.
  get loadLevel() {
    return this.currentLevel;
  }

  set loadLevel(level) {
    if (level !== -1 && level !== this.currentLevel) this.fetchLevel(level);
  }

  // Every handler is kept, in the order added, as hls.js does.
  on(event, handler) {
    this.handlers[event] = [...(this.handlers[event] ?? []), handler];
  }

  emit(event, data) {
    (this.handlers[event] ?? []).forEach((handler) => handler(event, data));
  }

  /** The manifest arrives: `levels` lowest bitrate first, `firstLevel` the one the master listed first. */
  manifest(levels, firstLevel) {
    // hls.js's own level objects carry its error marks: a level the test gives none starts with none.
    this.levels = levels.map((l) => ({ fragmentError: 0, loadError: 0, ...l }));
    this.emit(FakeHls.Events.MANIFEST_PARSED, { levels, firstLevel });
  }

  /** hls.js starts to fetch `level` (automatic choice or not). */
  fetchLevel(level) {
    this.currentLevel = level;
    this.emit(FakeHls.Events.LEVEL_SWITCHING, { level });
  }

  /** The playhead reaches `level`. */
  switchTo(level) {
    this.emit(FakeHls.Events.LEVEL_SWITCHED, { level });
  }

  /** hls.js starts to load a fragment; its `stats` are the live ones the loader updates. */
  fragLoading(frag) {
    this.emit(FakeHls.Events.FRAG_LOADING, { frag, targetBufferTime: 0 });
  }

  /** hls.js abandons the fragment in flight for another level. */
  fragAborted(frag) {
    this.emit(FakeHls.Events.FRAG_LOAD_EMERGENCY_ABORTED, { frag, stats: frag.stats });
  }

  /** hls.js 1.7 FRAG_LOADED payload: { frag, part, payload, networkDetails }; the stats live on `frag.stats`. */
  fragLoaded(stats) {
    this.emit(FakeHls.Events.FRAG_LOADED, {
      frag: { stats },
      part: null,
      payload: new ArrayBuffer(0),
      networkDetails: null,
    });
  }

  /** A media fragment is in the buffer. */
  fragBuffered(frag) {
    this.emit(FakeHls.Events.FRAG_BUFFERED, { frag, part: null, stats: frag.stats, id: 'main' });
  }

  /** A flush of `start` to `end` has finished (hls.js's error path sends 0 and 0). */
  bufferFlushed({ start = 0, end = 0, type = 'video' } = {}) {
    this.emit(FakeHls.Events.BUFFER_FLUSHED, { type, start, end });
  }

  error(data) {
    this.emit(FakeHls.Events.ERROR, data);
  }
}
