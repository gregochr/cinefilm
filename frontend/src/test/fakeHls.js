// A stand-in for hls.js at its event API: tests emit the events hls.js would and read what the player asked it.
import { vi } from 'vitest';

export class FakeHls {
  static instances = [];
  static supported = true;
  static Events = {
    MANIFEST_PARSED: 'hlsManifestParsed',
    LEVEL_SWITCHED: 'hlsLevelSwitched',
    FRAG_LOADED: 'hlsFragLoaded',
    ERROR: 'hlsError',
  };
  static ErrorTypes = {
    NETWORK_ERROR: 'networkError',
    MEDIA_ERROR: 'mediaError',
    OTHER_ERROR: 'otherError',
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
    this.config = config;
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

  on(event, handler) {
    this.handlers[event] = handler;
  }

  emit(event, data) {
    this.handlers[event]?.(event, data);
  }

  /** The manifest arrives: `levels` lowest bitrate first, `firstLevel` the one the master listed first. */
  manifest(levels, firstLevel) {
    this.levels = levels;
    this.emit(FakeHls.Events.MANIFEST_PARSED, { levels, firstLevel });
  }

  switchTo(level) {
    this.emit(FakeHls.Events.LEVEL_SWITCHED, { level });
  }

  fragLoaded(stats) {
    this.emit(FakeHls.Events.FRAG_LOADED, { stats });
  }

  error(data) {
    this.emit(FakeHls.Events.ERROR, data);
  }
}
