import { describe, expect, it } from 'vitest';
import { creditsCard, creditsPage, creditsPanel, creditsRow } from './creditsAdminCopy.js';

describe('creditsAdminCopy', () => {
  it('words the line-three reasons exactly as the plan says', () => {
    expect(creditsRow.plexNone).toBe('Plex has no marker for this film');
    expect(creditsRow.plexNoFingerprint).toBe(
      'This file hasn’t been fully scanned, so Plex’s marker can’t be matched to it',
    );
    expect(creditsRow.plexStale).toBe('Plex’s marker is for an older copy of this file');
    expect(creditsRow.plexUsable('1:52:10')).toBe(
      'Plex’s marker at 1:52:10 is ready but not applied yet',
    );
    expect(creditsRow.plexOutside).toBe('Plex’s marker is past the end of this file');
    expect(creditsRow.plexRefused('0:41:07')).toBe(
      'Plex’s marker at 0:41:07 is too early or too late to trust',
    );
    expect(creditsRow.noTimeline).toBe('This film’s length isn’t known');
  });

  it('words line two exactly as the plan says', () => {
    expect(creditsRow.noMarker(97)).toBe('No marker · the end screen shows at 97%');
    expect(creditsRow.fromPlex).toBe('From Plex');
    expect(creditsRow.yours).toBe('Yours');
    expect(creditsRow.detected).toBe('Detected');
    expect(creditsRow.notSettled).toBe('Not settled yet');
  });

  it('templates the panel strings', () => {
    expect(creditsPanel.stillsLabel('Heat')).toBe('Stills for Heat');
    expect(creditsPanel.frame('1:52:10')).toBe('Frame at 1:52:10');
    expect(creditsPanel.earlier).toBe('30 s earlier');
    expect(creditsPanel.atTime).toBe('At the time');
  });

  it('names the card and the page', () => {
    expect(creditsCard.review).toBe('Review end credits…');
    expect(creditsPage.look).toBe('Look and set…');
    expect(creditsPage.tabs).toEqual({
      none: 'Needs a look',
      plex: 'From Plex',
      owner: 'Yours',
      all: 'All',
    });
  });
});
