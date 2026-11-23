import { useState } from 'react';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import JourneyDelete from './JourneyDelete.jsx';

const journey = (over = {}) => ({
  id: 12,
  samples: 363,
  from: '2030-03-04T22:00:00Z',
  to: '2030-03-04T23:15:00Z',
  ...over,
});
const NAME = 'Delete the journey logged 4 Mar 2030, 22:00 to 23:15';
const opener = () => screen.getByRole('button', { name: NAME });

/** Owns what the page owns for it: which group is open, and `busy` while the confirmed request runs. */
function Harness({ send, problem = null, journey: shown = journey() }) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  return (
    <JourneyDelete
      journey={shown}
      open={open}
      busy={busy}
      problem={problem}
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
const group = () => screen.getByRole('group', { name: 'Delete this journey?' });

describe('JourneyDelete', () => {
  it('names the journey it deletes in the button’s spoken name', () => {
    render(<Harness send={vi.fn()} />);

    expect(opener()).toHaveTextContent('Delete…');
  });

  it('names a journey with no samples as having none', () => {
    render(<Harness send={vi.fn()} journey={journey({ from: null, to: null, samples: 0 })} />);

    expect(
      screen.getByRole('button', { name: 'Delete the journey with no samples' }),
    ).toBeInTheDocument();
  });

  it('opens the confirmation with its sentence and puts focus on Keep', async () => {
    render(<Harness send={vi.fn()} />);

    await userEvent.click(opener());

    expect(group()).toHaveTextContent(
      'Delete this journey and its 363 rounds? The log on the laptop is not touched.',
    );
    expect(screen.getByRole('button', { name: 'Delete journey' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Keep' })).toHaveFocus();
    expect(screen.queryByRole('button', { name: NAME })).not.toBeInTheDocument();
  });

  it('leaves the sentence without rounds for a journey with no samples', async () => {
    render(<Harness send={vi.fn()} journey={journey({ from: null, to: null, samples: 0 })} />);

    await userEvent.click(screen.getByRole('button', { name: /^Delete the journey/ }));

    expect(group()).toHaveTextContent('Delete this journey? The log on the laptop is not touched.');
  });

  it('closes on Keep and puts focus back on Delete…', async () => {
    const send = vi.fn();
    render(<Harness send={send} />);
    await userEvent.click(opener());

    await userEvent.click(screen.getByRole('button', { name: 'Keep' }));

    expect(screen.queryByRole('group')).not.toBeInTheDocument();
    expect(opener()).toHaveFocus();
    expect(send).not.toHaveBeenCalled();
  });

  it('closes on Escape from the focused button and puts focus back on Delete…', async () => {
    render(<Harness send={vi.fn()} />);
    await userEvent.click(opener());

    await userEvent.keyboard('{Escape}');

    expect(screen.queryByRole('group')).not.toBeInTheDocument();
    expect(opener()).toHaveFocus();
  });

  it('closes on Escape when focus is on the delete button', async () => {
    render(<Harness send={vi.fn()} />);
    await userEvent.click(opener());
    await userEvent.tab({ shift: true });
    expect(screen.getByRole('button', { name: 'Delete journey' })).toHaveFocus();

    await userEvent.keyboard('{Escape}');

    expect(screen.queryByRole('group')).not.toBeInTheDocument();
  });

  it('ignores a key other than Escape', async () => {
    render(<Harness send={vi.fn()} />);
    await userEvent.click(opener());

    await userEvent.keyboard('{ArrowRight}');

    expect(group()).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Keep' })).toHaveFocus();
  });

  it('asks the page to delete when Delete journey is pressed', async () => {
    const onConfirm = vi.fn();
    render(
      <JourneyDelete
        journey={journey()}
        open
        busy={false}
        problem={null}
        onOpen={() => {}}
        onKeep={() => {}}
        onConfirm={onConfirm}
      />,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Delete journey' }));

    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it('keeps both buttons live, not disabled, while no request runs', async () => {
    render(<Harness send={vi.fn()} />);
    await userEvent.click(opener());

    expect(screen.getByRole('button', { name: 'Delete journey' })).not.toHaveAttribute(
      'aria-disabled',
    );
    expect(screen.getByRole('button', { name: 'Keep' })).not.toHaveAttribute('aria-disabled');
  });

  describe('while the request runs', () => {
    const running = async () => {
      const { gate, release } = held();
      const send = vi.fn(() => gate);
      render(<Harness send={send} />);
      await userEvent.click(opener());
      await userEvent.click(screen.getByRole('button', { name: 'Delete journey' }));
      return { send, release };
    };

    it('reads Deleting… and marks both buttons aria-disabled, not disabled', async () => {
      await running();

      const deleting = screen.getByRole('button', { name: 'Deleting…' });
      expect(deleting).toHaveAttribute('aria-disabled', 'true');
      expect(deleting).not.toBeDisabled();
      expect(screen.getByRole('button', { name: 'Keep' })).toHaveAttribute('aria-disabled', 'true');
    });

    it('sends nothing for a second press of Deleting…', async () => {
      const { send } = await running();

      await userEvent.click(screen.getByRole('button', { name: 'Deleting…' }));

      expect(send).toHaveBeenCalledTimes(1);
    });

    it('does nothing on Keep and keeps the group open', async () => {
      await running();

      await userEvent.click(screen.getByRole('button', { name: 'Keep' }));

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

      expect(screen.getByRole('button', { name: 'Delete journey' })).not.toHaveAttribute(
        'aria-disabled',
      );
      expect(screen.getByRole('button', { name: 'Keep' })).not.toHaveAttribute('aria-disabled');
    });
  });

  it('does not open while a request of another row runs', async () => {
    const onOpen = vi.fn();
    render(
      <JourneyDelete
        journey={journey()}
        open={false}
        busy
        problem={null}
        onOpen={onOpen}
        onKeep={() => {}}
        onConfirm={() => {}}
      />,
    );

    expect(opener()).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(opener());

    expect(onOpen).not.toHaveBeenCalled();
  });

  it('shows a refusal as an alert with the group still open', async () => {
    render(<Harness send={vi.fn()} problem="Another request was running. Try again." />);
    await userEvent.click(opener());

    expect(screen.getByRole('alert')).toHaveTextContent('Another request was running. Try again.');
    expect(group()).toBeInTheDocument();
  });

  it('shows no alert when it has no refusal, as after a 401', async () => {
    render(<Harness send={vi.fn()} />);
    await userEvent.click(opener());

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('moves no focus when it is opened without a press', () => {
    render(
      <JourneyDelete
        journey={journey()}
        open
        busy={false}
        problem={null}
        onOpen={() => {}}
        onKeep={() => {}}
        onConfirm={() => {}}
      />,
    );

    expect(screen.getByRole('button', { name: 'Keep' })).not.toHaveFocus();
  });
});
