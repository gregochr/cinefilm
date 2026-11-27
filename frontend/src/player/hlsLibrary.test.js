// @vitest-environment node
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

describe('the installed hls.js', () => {
  it('is the version docs/engineering/stuck-rung-plan.md was written against', () => {
    const { version } = JSON.parse(
      readFileSync(new URL('../../node_modules/hls.js/package.json', import.meta.url), 'utf8'),
    );
    expect(
      version,
      'hls.js moved: read docs/engineering/stuck-rung-plan.md against the new source before the version moves ' +
        '(rungMarks.js goes if hls.js comes to expire a fragmentError mark itself)',
    ).toBe('1.7.4');
  });
});
