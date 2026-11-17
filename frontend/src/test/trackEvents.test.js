import { describe, expect, it } from 'vitest';
import { failTrack, loadTrack } from './trackEvents.js';

describe('trackEvents', () => {
  it('loadTrack sets readyState to 2 before the load event, and keeps it with no listener', () => {
    const listened = document.createElement('track');
    let seen;
    listened.addEventListener('load', () => {
      seen = listened.readyState;
    });
    const unheard = document.createElement('track');

    loadTrack(listened);
    loadTrack(unheard);

    expect(seen).toBe(2);
    expect(unheard.readyState).toBe(2);
  });

  it('failTrack sets readyState to 3 before the error event, and keeps it with no listener', () => {
    const listened = document.createElement('track');
    let seen;
    listened.addEventListener('error', () => {
      seen = listened.readyState;
    });
    const unheard = document.createElement('track');

    failTrack(listened);
    failTrack(unheard);

    expect(seen).toBe(3);
    expect(unheard.readyState).toBe(3);
  });
});
