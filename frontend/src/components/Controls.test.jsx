import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Picker, Segmented, Switch } from './Controls.jsx';

describe('Switch', () => {
  it('is a labelled switch reflecting its state and reporting the new value', async () => {
    const onChange = vi.fn();
    render(<Switch label="Dim the lights" checked={false} onChange={onChange} />);
    const toggle = screen.getByRole('switch', { name: 'Dim the lights' });
    expect(toggle).not.toBeChecked();
    await userEvent.click(toggle);
    expect(onChange).toHaveBeenCalledWith(true);
  });

  it('can be toggled from the keyboard', async () => {
    const onChange = vi.fn();
    render(<Switch label="Dim the lights" checked onChange={onChange} />);
    await userEvent.tab();
    expect(screen.getByRole('switch')).toHaveFocus();
    await userEvent.keyboard(' ');
    expect(onChange).toHaveBeenCalledWith(false);
  });
});

describe('Segmented', () => {
  const options = [
    { value: 'a', label: 'Alpha' },
    { value: 'b', label: 'Beta' },
  ];

  it('is a named group of toggle buttons with aria-pressed on the current value', () => {
    render(<Segmented label="Sort" options={options} value="b" onChange={() => {}} />);
    const group = screen.getByRole('group', { name: 'Sort' });
    expect(within(group).getByRole('button', { name: 'Alpha' })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
    expect(within(group).getByRole('button', { name: 'Beta' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  it('reports the chosen value', async () => {
    const onChange = vi.fn();
    render(<Segmented label="Sort" options={options} value="b" onChange={onChange} />);
    await userEvent.click(screen.getByRole('button', { name: 'Alpha' }));
    expect(onChange).toHaveBeenCalledWith('a');
  });
});

describe('Picker', () => {
  const options = [
    { value: '', label: 'All' },
    { value: 'x', label: 'Ex' },
    { value: 'y', label: 'Why', disabled: true },
  ];

  it('is a labelled select that reports the chosen value', async () => {
    const onChange = vi.fn();
    render(<Picker label="Genre" value="" onChange={onChange} options={options} />);
    await userEvent.selectOptions(screen.getByLabelText('Genre'), 'Ex');
    expect(onChange).toHaveBeenCalledWith('x');
  });

  it('disables unavailable options', () => {
    render(<Picker label="Genre" value="" onChange={() => {}} options={options} />);
    expect(screen.getByRole('option', { name: 'Why' })).toBeDisabled();
  });

  it('shows extra content under the select', () => {
    render(
      <Picker label="Genre" value="" onChange={() => {}} options={options}>
        <a href="/more">More</a>
      </Picker>,
    );
    expect(screen.getByRole('link', { name: 'More' })).toBeInTheDocument();
  });

  it('renders the inert state as a labelled group instead of a select', () => {
    render(
      <Picker label="Subtitles" inertText="None for this film">
        <a href="/admin">Find subtitles in Admin</a>
      </Picker>,
    );
    expect(screen.getByRole('group', { name: 'Subtitles' })).toHaveTextContent(
      'None for this film',
    );
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Find subtitles in Admin' })).toBeInTheDocument();
  });
});
