import { describe, expect, it } from 'vitest';
import { fullSubtitleLine, fullNeedReason } from './fullNeed.js';

describe('a film’s full subtitle line', () => {
  it('says whose file plays, a download that is not checked yet, or none', () => {
    expect(fullSubtitleLine({ fullSubtitle: { source: 'SIDECAR' } })).toBe('Playing: your file');
    expect(fullSubtitleLine({ fullSubtitle: { source: 'DOWNLOAD' } })).toBe(
      'Playing: download (not checked yet)',
    );
    expect(fullSubtitleLine({ fullSubtitle: null })).toBe('None yet');
    expect(fullSubtitleLine({})).toBe('None yet');
  });
});

describe('wording', () => {
  it('words each reason in plain language', () => {
    const words = (reason) => fullNeedReason({ reason });
    expect(words('FOREIGN_AUDIO')).toBe('Audio is in another language');
    expect(words('ENGLISH_AUDIO')).toBe('Audio is English');
    expect(words('ORIGINAL_AUDIO_ASSUMED')).toBe('Audio not labelled: original language assumed');
    expect(words('ENGLISH_FILM')).toBe('English-language film');
    expect(words('OWNER')).toBe('Set by you');
    expect(words('NONE_EXIST')).toBe('No English subtitles exist (set by you)');
    expect(words('NO_LANGUAGES')).toBe('Waiting for TMDB');
    expect(words('SOMETHING_NEW')).toBe('');
  });
});
