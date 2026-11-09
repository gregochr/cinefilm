import { describe, expect, it } from 'vitest';
import { resolutionLabel } from './resolution.js';

describe('resolutionLabel', () => {
  it.each([
    [1920, 796, '1080p'],
    [1920, 1080, '1080p'],
    [1280, 536, '720p'],
    [3840, 1600, '2160p (4K)'],
    [720, 576, '480p'],
    [1919, 800, '720p'],
    [1279, 536, '480p'],
    [3839, 1600, '1080p'],
  ])('%i x %i reads %s', (width, _height, label) => {
    expect(resolutionLabel(width)).toBe(label);
  });

  it('gives no label when the width is unknown', () => {
    expect(resolutionLabel(null)).toBeNull();
    expect(resolutionLabel(undefined)).toBeNull();
    expect(resolutionLabel(0)).toBeNull();
  });
});
