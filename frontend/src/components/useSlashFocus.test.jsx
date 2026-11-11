import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { isTypingTarget, useSlashFocus } from './useSlashFocus.js';

// A real consumer would be Layout; this page has the hook plus the search and other fields it must respect.
function Page({ withSearch = true }) {
  useSlashFocus();
  return (
    <div>
      {withSearch ? <input type="search" aria-label="Search" data-global-search /> : null}
      <input aria-label="Notes" />
      <textarea aria-label="Comment" />
      <select aria-label="Pick">
        <option>a</option>
      </select>
      <button type="button">Other</button>
    </div>
  );
}

describe('"/" focuses search', () => {
  it('ignores "/" pressed inside an open modal dialog', async () => {
    render(
      <>
        <Page />
        <div role="dialog" aria-modal="true" aria-label="Modal">
          <button type="button">Inside</button>
        </div>
      </>,
    );
    screen.getByRole('button', { name: 'Inside' }).focus();
    await userEvent.keyboard('/');
    expect(screen.getByRole('searchbox', { name: 'Search' })).not.toHaveFocus();
  });

  it('focuses the search field when nothing is being typed into', async () => {
    render(<Page />);
    await userEvent.keyboard('/');
    expect(screen.getByRole('searchbox', { name: 'Search' })).toHaveFocus();
  });

  it('works from a focused button too', async () => {
    render(<Page />);
    screen.getByRole('button', { name: 'Other' }).focus();
    await userEvent.keyboard('/');
    expect(screen.getByRole('searchbox', { name: 'Search' })).toHaveFocus();
  });

  it('does not type the slash into the search field it focuses', async () => {
    render(<Page />);
    await userEvent.keyboard('/');
    expect(screen.getByRole('searchbox', { name: 'Search' })).toHaveValue('');
  });

  it.each([
    ['an input', 'textbox', 'Notes'],
    ['a textarea', 'textbox', 'Comment'],
    ['a select', 'combobox', 'Pick'],
  ])('leaves a slash typed into %s alone', async (_what, role, name) => {
    render(<Page />);
    const field = screen.getByRole(role, { name });
    field.focus();
    await userEvent.keyboard('/');
    expect(field).toHaveFocus();
    if (role === 'textbox') expect(field).toHaveValue('/');
  });

  it.each([
    ['Ctrl', '{Control>}/{/Control}'],
    ['Meta', '{Meta>}/{/Meta}'],
    ['Alt', '{Alt>}/{/Alt}'],
  ])('ignores %s+/ so browser shortcuts still work', async (_name, keys) => {
    render(<Page />);
    await userEvent.keyboard(keys);
    expect(screen.getByRole('searchbox', { name: 'Search' })).not.toHaveFocus();
  });

  it('ignores other keys', async () => {
    render(<Page />);
    await userEvent.keyboard('a');
    expect(screen.getByRole('searchbox', { name: 'Search' })).not.toHaveFocus();
  });

  it('does nothing on a page with no search field', async () => {
    render(<Page withSearch={false} />);
    screen.getByRole('button', { name: 'Other' }).focus();
    await userEvent.keyboard('/');
    expect(screen.getByRole('button', { name: 'Other' })).toHaveFocus();
  });

  it('stops listening when the page unmounts', async () => {
    const { unmount } = render(<Page />);
    unmount();
    const outside = document.createElement('input');
    outside.setAttribute('data-global-search', '');
    document.body.append(outside);
    await userEvent.keyboard('/');
    expect(outside).not.toHaveFocus();
    outside.remove();
  });
});

describe('isTypingTarget', () => {
  it('is false for no target and for a target without a tag', () => {
    expect(isTypingTarget(null)).toBe(false);
    expect(isTypingTarget({})).toBe(false);
  });

  it('is true for form fields and contenteditable, false for other elements', () => {
    for (const tag of ['input', 'textarea', 'select']) {
      expect(isTypingTarget(document.createElement(tag))).toBe(true);
    }
    expect(isTypingTarget({ tagName: 'DIV', isContentEditable: true })).toBe(true);
    expect(isTypingTarget(document.createElement('button'))).toBe(false);
  });
});
