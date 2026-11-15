import { describe, expect, it } from 'vitest';
import { buttons } from './adminCopy.js';
import {
  adminRemoveLabels,
  fixRemoveLabels,
  subtitleFix,
  subtitlesWrong,
} from './subtitleFixCopy.js';

describe('subtitleFixCopy', () => {
  it('names the entry as AR7 does, with a spoken name that says what it opens', () => {
    expect(subtitleFix.entry).toEqual({ label: 'Fix…', spoken: 'Fix subtitles…' });
  });

  it('opens the removing flow with the renamed button of AR7 (07), keeping Admin’s old words apart', () => {
    expect(fixRemoveLabels.open).toBe(buttons.deleteDownload.label);
    expect(fixRemoveLabels.open).toBe('Delete this download');
    expect(adminRemoveLabels.open).toBe('Remove');
    expect(adminRemoveLabels.confirm).toBe('Remove download');
  });

  it('names the film in the spoken name of a removing button, so two downloads are told apart', () => {
    expect(fixRemoveLabels.openSpoken('Heat.1995')).toBe('Delete this download: Heat.1995');
    expect(adminRemoveLabels.openSpoken('Heat.1995')).toBe(
      'Remove the OpenSubtitles download Heat.1995',
    );
  });

  it('has the five renamed buttons (07) the Fix… panel uses, with their hints', () => {
    expect(buttons.useMyFileInstead.label).toBe('Use my file instead');
    expect(buttons.previewLines.label).toBe('Preview lines');
    expect(buttons.letCineFilmDecide.label).toBe('Let CineFilm decide');
    expect(buttons.deleteDownload.label).toBe('Delete this download');
    expect(buttons.addFileToFilm.label).toBe('Add my own file…');
  });

  it('says what "Subtitles are wrong" did in one short line, and why it failed in another', () => {
    expect(subtitlesWrong.label).toBe('Subtitles are wrong');
    expect(subtitlesWrong.done).toBe('Subtitle download removed. CineFilm won’t use it again.');
    expect(subtitlesWrong.failed).toBe('Couldn’t remove the download. Try again.');
    expect(subtitlesWrong.lookupFailed).toBe('Couldn’t find that download to remove.');
    expect(subtitlesWrong.busy).toBe('Removing…');
  });
});
