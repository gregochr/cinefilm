// A stand-in for hls.js at its event API: tests emit the events hls.js would and read what the player asked it.
import { vi } from 'vitest';

export class FakeHls {
  static instances = [];
  static supported = true;
  static Events = {
    MANIFEST_PARSED: 'hlsManifestParsed',
    LEVEL_SWITCHING: 'hlsLevelSwitching',
    LEVEL_SWITCHED: 'hlsLevelSwitched',
    FRAG_LOADED: 'hlsFragLoaded',
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
    this.config = { minAutoBitrate: 0, ...config };
    this.handlers = {};
    this.levels = [];
    this.autoLevelCapping = -1;
    this.loadSource = vi.fn();
    this.attachMedia = vi.fn();
    this.startLoad = vi.fn();
    this.recoverMediaError = vi.fn();
    this.destroy = vi.fn();
    FakeHls.instances.push(this);
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
    this.levels = levels;
    this.emit(FakeHls.Events.MANIFEST_PARSED, { levels, firstLevel });
  }

  /** hls.js starts to fetch `level` (automatic choice or not). */
  fetchLevel(level) {
    this.emit(FakeHls.Events.LEVEL_SWITCHING, { level });
  }

  /** The playhead reaches `level`. */
  switchTo(level) {
    this.emit(FakeHls.Events.LEVEL_SWITCHED, { level });
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

  error(data) {
    this.emit(FakeHls.Events.ERROR, data);
  }
}
