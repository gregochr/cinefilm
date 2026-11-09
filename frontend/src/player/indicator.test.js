import { describe, expect, it } from 'vitest';
import { indicatorModel } from './indicator.js';

const model = (over) =>
  indicatorModel({ quality: 'auto', rung: 'original', top: 'original', kbps: null, ...over });

describe('the corner indicator text', () => {
  it('reads "Auto · Original · Home" for Auto at the top rung at home', () => {
    expect(model({ kbps: 30000 }).text).toBe('Auto · Original · Home');
  });

  it('reads "Original · Home" when Original is chosen', () => {
    expect(model({ quality: 'original', kbps: 30000 }).text).toBe('Original · Home');
  });

  it('reads "720p · Remote" for a pinned 720p on a link under 20 Mbps', () => {
    expect(model({ quality: '720p', rung: '720p', kbps: 8000 }).text).toBe('720p · Remote');
  });

  it('reads "480p · Remote · slow" for a pinned 480p', () => {
    expect(model({ quality: '480p', rung: '480p', kbps: 2000 }).text).toBe('480p · Remote · slow');
  });

  it('puts Auto first when Auto has stepped down: "Auto · 480p · Remote · slow"', () => {
    expect(model({ rung: '480p', kbps: 2000 }).text).toBe('Auto · 480p · Remote · slow');
  });

  it('calls 360p and 240p slow too, and 720p not', () => {
    expect(model({ quality: '360p', rung: '360p', kbps: 900 }).text).toBe('360p · Remote · slow');
    expect(model({ quality: '240p', rung: '240p', kbps: 900 }).text).toBe('240p · Remote · slow');
    expect(model({ quality: '720p', rung: '720p', kbps: 900 }).slow).toBe(false);
  });
});

describe('Home is a median of 20 Mbps or more', () => {
  it('19,999 kbit/s is Remote and 20,000 is Home', () => {
    expect(model({ kbps: 19999 }).text).toBe('Auto · Original · Remote');
    expect(model({ kbps: 20000 }).text).toBe('Auto · Original · Home');
  });

  it('with nothing measured yet, only the top rung counts as home', () => {
    expect(model({ kbps: null }).text).toBe('Auto · Original · Home');
    expect(model({ rung: '720p', kbps: null }).text).toBe('Auto · 720p · Remote');
  });
});

describe('the amber dot', () => {
  it('is green at home on the top rung', () => {
    expect(model({ kbps: 30000 }).amber).toBe(false);
  });

  it('is amber when remote, even on the top rung', () => {
    expect(model({ kbps: 5000 }).amber).toBe(true);
  });

  it('is amber when Auto sits below the top rung, even on a fast link', () => {
    expect(model({ rung: '720p', kbps: 30000 }).amber).toBe(true);
  });

  it('is not amber for a pinned lower rung on a fast link (a choice, not a degradation)', () => {
    expect(model({ quality: '720p', rung: '720p', kbps: 30000 }).amber).toBe(false);
  });

  it('is amber for a slow rung however it got there', () => {
    expect(model({ quality: '480p', rung: '480p', kbps: 30000 }).amber).toBe(true);
  });
});
