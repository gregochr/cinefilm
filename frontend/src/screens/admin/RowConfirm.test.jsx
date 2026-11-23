import { useState } from 'react';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import RowConfirm from './RowConfirm.jsx';

const opener = {
  id: 'open-thing',
  className: 'a b',
  label: 'Do thing…',
  name: 'Do the thing to item 7',
};
const words = {
  group: 'Do this thing?',
  sentence: 'Do the thing to item 7?',
  confirm: 'Do thing',
  busy: 'Doing…',
  keep: 'Keep it',
};
const OPEN = () => screen.getByRole('button', { name: 'Do the thing to item 7' });
const group = () => screen.getByRole('group', { name: 'Do this thing?' });

const props = (over = {}) => ({
  opener,
  confirmClassName: 'c',
  words,
  open: false,
  busy: false,
  problem: null,
  onOpen: () => {},
  onKeep: () => {},
  onConfirm: () => {},
  ...over,
});

/** Owns what the page owns for it: which group is open, and `busy` while the confirmed request runs. */
function Harness({ send }) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  return (
    <RowConfirm
      {...props()}
      open={open}
      busy={busy}
      onOpen={() => setOpen(true)}
      onKeep={() => setOpen(false)}
      onConfirm={async () => {
        setBusy(true);
        await send();
        setBusy(false);
      }}
    />
  );
}
const held = () => {
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  return { gate, release };
};
const beside = (over) => (
  <>
    <button type="button">Elsewhere</button>
    <RowConfirm {...props(over)} />
  </>
);

describe('RowConfirm', () => {
  it('shows the opener with its visible words, its spoken name and its id', () => {
    render(<RowConfirm {...props()} />);

    expect(OPEN()).toHaveTextContent('Do thing…');
    expect(OPEN()).toHaveAttribute('id', 'open-thing');
  });

  it('opens the group with its sentence and puts focus on Keep when the opener is pressed', async () => {
    render(<Harness send={vi.fn()} />);

    await userEvent.click(OPEN());

    expect(group()).toHaveTextContent('Do the thing to item 7?');
    expect(screen.getByRole('button', { name: 'Do thing' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Keep it' })).toHaveFocus();
    expect(
      screen.queryByRole('button', { name: 'Do the thing to item 7' }),
    ).not.toBeInTheDocument();
  });

  it('closes on Keep and puts focus back on the opener', async () => {
    const send = vi.fn();
    render(<Harness send={send} />);
    await userEvent.click(OPEN());

    await userEvent.click(screen.getByRole('button', { name: 'Keep it' }));

    expect(screen.queryByRole('group')).not.toBeInTheDocument();
    expect(OPEN()).toHaveFocus();
    expect(send).not.toHaveBeenCalled();
  });

  it('closes on Escape from the focused button and puts focus back on the opener', async () => {
    render(<Harness send={vi.fn()} />);
    await userEvent.click(OPEN());

    await userEvent.keyboard('{Escape}');

    expect(screen.queryByRole('group')).not.toBeInTheDocument();
    expect(OPEN()).toHaveFocus();
  });

  it('closes on Escape when focus is on the confirming button', async () => {
    render(<Harness send={vi.fn()} />);
    await userEvent.click(OPEN());
    await userEvent.tab({ shift: true });
    expect(screen.getByRole('button', { name: 'Do thing' })).toHaveFocus();

    await userEvent.keyboard('{Escape}');

    expect(screen.queryByRole('group')).not.toBeInTheDocument();
  });

  it('ignores a key other than Escape', async () => {
    render(<Harness send={vi.fn()} />);
    await userEvent.click(OPEN());

    await userEvent.keyboard('{ArrowRight}');

    expect(group()).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Keep it' })).toHaveFocus();
  });

  it('asks the page to act when the confirming button is pressed', async () => {
    const onConfirm = vi.fn();
    render(<RowConfirm {...props({ open: true, onConfirm })} />);

    await userEvent.click(screen.getByRole('button', { name: 'Do thing' }));

    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it('keeps both buttons live, not disabled, while no request runs', () => {
    render(<RowConfirm {...props({ open: true })} />);

    expect(screen.getByRole('button', { name: 'Do thing' })).not.toHaveAttribute('aria-disabled');
    expect(screen.getByRole('button', { name: 'Keep it' })).not.toHaveAttribute('aria-disabled');
  });

  it('moves no focus when it is mounted already open', () => {
    render(<RowConfirm {...props({ open: true })} />);

    expect(screen.getByRole('button', { name: 'Keep it' })).not.toHaveFocus();
  });

  it('moves no focus that is somewhere real when it is closed without a press', () => {
    const { rerender } = render(beside({ open: true }));
    screen.getByRole('button', { name: 'Elsewhere' }).focus();

    rerender(beside({}));

    expect(screen.getByRole('button', { name: 'Elsewhere' })).toHaveFocus();
    expect(OPEN()).not.toHaveFocus();
  });

  it('puts focus on the opener when it is closed without a press and focus has fallen to the page', () => {
    const { rerender } = render(<RowConfirm {...props({ open: true })} />);
    act(() => screen.getByRole('button', { name: 'Keep it' }).blur());
    expect(document.body).toHaveFocus();

    rerender(<RowConfirm {...props()} />);

    expect(OPEN()).toHaveFocus();
  });

  describe('while the request runs', () => {
    const running = async () => {
      const { gate, release } = held();
      const send = vi.fn(() => gate);
      render(<Harness send={send} />);
      await userEvent.click(OPEN());
      await userEvent.click(screen.getByRole('button', { name: 'Do thing' }));
      return { send, release };
    };

    it('reads the busy word and marks both buttons aria-disabled, not disabled', async () => {
      await running();

      const doing = screen.getByRole('button', { name: 'Doing…' });
      expect(doing).toHaveAttribute('aria-disabled', 'true');
      expect(doing).not.toBeDisabled();
      expect(screen.getByRole('button', { name: 'Keep it' })).toHaveAttribute(
        'aria-disabled',
        'true',
      );
    });

    it('sends nothing for a second press of the busy button', async () => {
      const { send } = await running();

      await userEvent.click(screen.getByRole('button', { name: 'Doing…' }));

      expect(send).toHaveBeenCalledTimes(1);
    });

    it('does nothing on Keep and keeps the group open', async () => {
      await running();

      await userEvent.click(screen.getByRole('button', { name: 'Keep it' }));

      expect(group()).toBeInTheDocument();
    });

    it('does nothing on Escape and keeps the group open', async () => {
      await running();

      await userEvent.keyboard('{Escape}');

      expect(group()).toBeInTheDocument();
    });

    it('is live again when the request has ended', async () => {
      const { release } = await running();

      await act(async () => release());

      expect(screen.getByRole('button', { name: 'Do thing' })).not.toHaveAttribute('aria-disabled');
      expect(screen.getByRole('button', { name: 'Keep it' })).not.toHaveAttribute('aria-disabled');
    });
  });

  it('does not open while a request of another row runs', async () => {
    const onOpen = vi.fn();
    render(<RowConfirm {...props({ busy: true, onOpen })} />);

    expect(OPEN()).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(OPEN());

    expect(onOpen).not.toHaveBeenCalled();
  });

  it('shows a problem as an alert with the group still open', () => {
    render(<RowConfirm {...props({ open: true, problem: 'It went wrong.' })} />);

    expect(screen.getByRole('alert')).toHaveTextContent('It went wrong.');
    expect(group()).toBeInTheDocument();
  });

  it('shows no alert when it has no problem', () => {
    render(<RowConfirm {...props({ open: true })} />);

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
