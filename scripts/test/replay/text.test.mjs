// scripts/replay/text.mjs: the one function every printed line and every text from the page passes through, and the
// rule for a run's folder name.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { isRunName, printable } from '../../replay/text.mjs';

describe('printable', () => {
  test('plain text passes unchanged', () => {
    assert.equal(printable('Auto - 480p (slow) ~ 100%', 100), 'Auto - 480p (slow) ~ 100%');
  });

  test('each kind of line break becomes one space, so a text cannot start a line of its own', () => {
    assert.equal(printable('one\ntwo\r\nthree\rfour', 100), 'one two  three four');
  });

  test('a character outside printable ASCII becomes a question mark: tab, escape, delete, accents, a smiley', () => {
    assert.equal(printable('a\tb\u001b[31mc\u007fd\u00e9e \u263a f', 100), 'a?b?[31mc?d?e ? f');
  });

  test('the few signs the app uses are written as plain equivalents, each from its own table entry', () => {
    assert.equal(printable('Auto \u00b7 480p \u00b7 Remote \u00b7 slow', 100), 'Auto - 480p - Remote - slow');
    assert.equal(printable('You\u2019re \u2018here\u2019', 100), "You're 'here'");
    assert.equal(printable('\u201cquoted\u201d', 100), '"quoted"');
    assert.equal(printable('a\u2013b\u2014c', 100), 'a-b-c');
    assert.equal(printable('wait\u2026', 100), 'wait...');
  });

  test('the cut at the limit counts the text as written out: an ellipsis takes three places', () => {
    assert.equal(printable('ab\u2026', 4), 'ab..');
    assert.equal(printable('ab\u2026', 5), 'ab...');
    assert.equal(printable('ab\u2026cd', 6), 'ab...c');
  });

  test('a line break is still a space before anything else, and a sign next to an accent keeps both rules', () => {
    assert.equal(printable('a\n\u00b7\u00e9', 100), 'a -?');
  });

  test('the first and last printable characters (space and tilde) are kept', () => {
    assert.equal(printable(' ~', 10), ' ~');
  });

  test('a character outside the basic plane is two question marks', () => {
    assert.equal(printable('\u{1F600}', 10), '??');
  });

  test('text of exactly the limit is kept whole, one more character is cut off', () => {
    assert.equal(printable('abcde', 5), 'abcde');
    assert.equal(printable('abcdef', 5), 'abcde');
  });

  test('the limit applies after line breaks became spaces', () => {
    assert.equal(printable('ab\ncd', 3), 'ab ');
  });

  test('anything that is not text is made into text first', () => {
    assert.equal(printable(undefined, 20), 'undefined');
    assert.equal(printable(42, 20), '42');
    assert.equal(printable(new Error('boom\nsecond line'), 20), 'Error: boom second l');
  });
});

describe('isRunName', () => {
  test('letters, digits, dot, dash and underscore are a name', () => {
    assert.equal(isRunName('run-24-15-sampled'), true);
    assert.equal(isRunName('Hand_flat.2'), true);
    assert.equal(isRunName('...'), true);
  });

  test('60 characters are a name, 61 are not, and nothing is not', () => {
    assert.equal(isRunName('a'.repeat(60)), true);
    assert.equal(isRunName('a'.repeat(61)), false);
    assert.equal(isRunName(''), false);
  });

  test('a single dot and two dots are not names: they are folders already', () => {
    assert.equal(isRunName('.'), false);
    assert.equal(isRunName('..'), false);
  });

  test('a slash, a backslash, a space, a line break and a null are not allowed', () => {
    for (const bad of ['a/b', '../x', 'a\\b', 'a b', 'a\nb', 'a\0b', 'a:b']) {
      assert.equal(isRunName(bad), false, JSON.stringify(bad));
    }
  });

  test('something that is not text is not a name', () => {
    assert.equal(isRunName(undefined), false);
    assert.equal(isRunName(12), false);
  });
});
