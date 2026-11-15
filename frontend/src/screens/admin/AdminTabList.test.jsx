import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import AdminTabList, { panelId, tabId } from './AdminTabList.jsx';

const TABS = [
  { key: 'foreign', label: 'Foreign-language films', count: 4 },
  { key: 'forced', label: 'Forced subtitles not found', count: null },
];

function Harness() {
  const [selected, setSelected] = useState('foreign');
  return (
    <AdminTabList label="Subtitle lists" tabs={TABS} selected={selected} onSelect={setSelected} />
  );
}

describe('AdminTabList', () => {
  it('is a tablist whose tabs name their panels, with the count after the label once known', () => {
    render(<Harness />);

    expect(screen.getByRole('tablist', { name: 'Subtitle lists' })).toBeInTheDocument();
    const foreign = screen.getByRole('tab', { name: 'Foreign-language films 4' });
    expect(foreign).toHaveAttribute('aria-selected', 'true');
    expect(foreign).toHaveAttribute('aria-controls', panelId('foreign'));
    expect(foreign).toHaveAttribute('id', tabId('foreign'));
    // No count yet: just the label, never a "null" or a zero.
    expect(screen.getByRole('tab', { name: 'Forced subtitles not found' })).toHaveAttribute(
      'aria-selected',
      'false',
    );
  });

  it('keeps only the selected tab in the tab order', () => {
    render(<Harness />);

    expect(screen.getByRole('tab', { name: /Foreign/ })).toHaveAttribute('tabindex', '0');
    expect(screen.getByRole('tab', { name: /Forced/ })).toHaveAttribute('tabindex', '-1');
  });

  it('selects a tab on click', async () => {
    render(<Harness />);

    await userEvent.click(screen.getByRole('tab', { name: /Forced/ }));

    expect(screen.getByRole('tab', { name: /Forced/ })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: /Foreign/ })).toHaveAttribute('aria-selected', 'false');
  });

  it('moves selection and focus with the arrows (wrapping), Home and End, and ignores other keys', async () => {
    render(<Harness />);
    const foreign = screen.getByRole('tab', { name: /Foreign/ });
    const forced = screen.getByRole('tab', { name: /Forced/ });
    foreign.focus();

    await userEvent.keyboard('{ArrowRight}');
    expect(forced).toHaveFocus();
    expect(forced).toHaveAttribute('aria-selected', 'true');
    await userEvent.keyboard('{ArrowRight}');
    expect(foreign).toHaveFocus();
    await userEvent.keyboard('{ArrowLeft}');
    expect(forced).toHaveFocus();
    await userEvent.keyboard('{Home}');
    expect(foreign).toHaveFocus();
    await userEvent.keyboard('{End}');
    expect(forced).toHaveFocus();
    await userEvent.keyboard('a');
    expect(forced).toHaveFocus();
    expect(forced).toHaveAttribute('aria-selected', 'true');
  });
});
