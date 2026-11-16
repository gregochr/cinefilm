import { describe, expect, it } from 'vitest';
import { buttons } from './adminCopy.js';
import { fixRemoveLabels, subtitleCheck, subtitleFix, subtitlesWrong } from './subtitleFixCopy.js';

describe('subtitleFixCopy', () => {
  it('names the entry as AR7 does, with a spoken name that says what it opens', () => {
    expect(subtitleFix.entry).toEqual({ label: 'Fix…', spoken: 'Fix subtitles…' });
  });

  it('opens the removing flow with the renamed button of AR7 (07)', () => {
    expect(fixRemoveLabels.open).toBe(buttons.deleteDownload.label);
    expect(fixRemoveLabels.open).toBe('Delete this download');
    expect(fixRemoveLabels.confirm).toBe('Delete download');
  });

  it('words "Looks right" and its undo as Admin did, with the planner’s hint and notices', () => {
    expect(subtitleCheck.label).toBe('Looks right');
    expect(subtitleCheck.undo).toBe('Undo');
    expect(subtitleCheck.checked).toBe('You checked this: the lines look right.');
    expect(subtitleCheck.hint).toBe('Marks the lines as checked: you’ve seen they’re right.');
    expect(subtitleCheck.done).toBe('Marked as checked.');
    expect(subtitleCheck.undone).toBe('The check is taken away.');
    expect(subtitleCheck.undoneReplaced).toBe('The download plays again.');
  });

  it('names the film in the spoken name of a removing button, so two downloads are told apart', () => {
    expect(fixRemoveLabels.openSpoken('Heat.1995')).toBe('Delete this download: Heat.1995');
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
    expect(subtitlesWrong.changed).toBe(
      'This subtitle has changed since the film started. Reload to see the new one.',
    );
    expect(subtitlesWrong.busy).toBe('Removing…');
  });
});
