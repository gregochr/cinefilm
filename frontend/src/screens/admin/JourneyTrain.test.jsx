import { useState } from 'react';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { readTrainFile } from '../../state/trainFile.js';
import { readRepoJson } from '../../test/repoFile.js';
import JourneyTrain from './JourneyTrain.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

const EXAMPLE = JSON.stringify(
  readRepoJson('backend/src/test/resources/journey/train-file-example.json'),
);
const journey = (over = {}) => ({
  id: 12,
  samples: 363,
  from: '2030-03-04T22:00:00Z',
  to: '2030-03-04T23:15:00Z',
  train: null,
  ...over,
});
const ADD = 'Add train file to the journey logged 4 Mar 2030, 22:00 to 23:15';
const REPLACE = 'Replace train file of the journey logged 4 Mar 2030, 22:00 to 23:15';
const opener = (name = ADD) => screen.getByRole('button', { name });
const input = () => screen.getByLabelText('Train file');
const file = (text, name = 'run.json') => new File([text], name);
const choose = (text, name) => userEvent.upload(input(), file(text, name));
const group = () => screen.getByRole('group', { name: 'Train file for this journey' });
const held = () => {
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  return { gate, release };
};

/** Owns what the page owns for it: which panel is open, `busy` while the sent file runs, and a refusal. */
function Harness({ send, problem: first = null, journey: shown = journey(), startOpen = false }) {
  const [open, setOpen] = useState(startOpen);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState(first);
  return (
    <>
      <JourneyTrain
        journey={shown}
        open={open}
        busy={busy}
        problem={problem}
        onOpen={() => setOpen(true)}
        onClose={() => setOpen(false)}
        onGive={async (request) => {
          setBusy(true);
          await send(request);
          setBusy(false);
        }}
        onEdit={() => setProblem(null)}
      />
      <button type="button" onClick={() => setOpen(false)}>
        Elsewhere
      </button>
    </>
  );
}

describe('JourneyTrain', () => {
  it('names the journey in the spoken name of Add train file…', () => {
    render(<Harness send={vi.fn()} />);

    expect(opener()).toHaveTextContent('Add train file…');
  });

  it('says Replace train file… for a journey that has a train', () => {
    render(<Harness send={vi.fn()} journey={journey({ train: { headcode: '9Z99' } })} />);

    expect(opener(REPLACE)).toHaveTextContent('Replace train file…');
  });

  it('names a journey with no samples as having none, both ways', () => {
    const none = { from: null, to: null, samples: 0 };
    const { rerender } = render(<Harness send={vi.fn()} journey={journey(none)} />);
    expect(
      screen.getByRole('button', { name: 'Add train file to the journey with no samples' }),
    ).toBeInTheDocument();

    rerender(<Harness send={vi.fn()} journey={journey({ ...none, train: { headcode: 'x' } })} />);

    expect(
      screen.getByRole('button', {
        name: 'Replace train file of the journey with no samples',
      }),
    ).toBeInTheDocument();
  });

  it('opens the panel with its sentence and the labelled file input, and puts focus on the input', async () => {
    render(<Harness send={vi.fn()} />);
    expect(opener()).toHaveAttribute('aria-expanded', 'false');

    await userEvent.click(opener());

    expect(group()).toHaveTextContent(
      'Choose the file with this train’s run: its timetable id, running date, headcode and its times at each timing point. The whole file is sent.',
    );
    expect(input()).toHaveFocus();
    expect(input()).toHaveAttribute('type', 'file');
    expect(input()).toHaveAttribute('accept', '.json');
    expect(opener()).toHaveAttribute('aria-expanded', 'true');
    expect(opener()).toHaveAttribute('aria-controls', group().id);
    expect(screen.queryByRole('button', { name: 'Add train' })).not.toBeInTheDocument();
  });

  it('moves no focus when it is opened without a press', () => {
    render(<Harness send={vi.fn()} startOpen />);

    expect(input()).not.toHaveFocus();
  });

  it('closes when the button that opened it is pressed again', async () => {
    render(<Harness send={vi.fn()} />);
    await userEvent.click(opener());

    await userEvent.click(opener());

    expect(screen.queryByRole('group')).not.toBeInTheDocument();
    expect(opener()).toHaveFocus();
  });

  it('shows a good file’s ready line and every term and value, and a button that adds', async () => {
    render(<Harness send={vi.fn()} />);
    await userEvent.click(opener());

    await choose(EXAMPLE);

    expect(await screen.findByRole('status')).toHaveTextContent('Ready to send: train 9Z99');
    expect(screen.getByRole('heading', { name: 'What will be sent' })).toBeInTheDocument();
    expect(screen.getAllByRole('term').map((t) => t.textContent)).toEqual([
      'Timetable id',
      'Running date',
      'Headcode',
      'Timing points',
      'From',
      'To',
    ]);
    expect(screen.getAllByRole('definition').map((d) => d.textContent)).toEqual([
      'X12345',
      '4 Mar 2030',
      '9Z99',
      '8',
      'Alpha Junction',
      'Theta Central',
    ]);
    expect(screen.getByRole('button', { name: 'Add train' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('writes a date that is not YYYY-MM-DD as the file has it', async () => {
    render(<Harness send={vi.fn()} />);
    await userEvent.click(opener());

    await choose(JSON.stringify({ ...JSON.parse(EXAMPLE), date: '04/03/2030' }));

    await screen.findByRole('status');
    expect(screen.getAllByRole('definition')[1]).toHaveTextContent('04/03/2030');
  });

  it('reads Replace train on the button for a journey that has a train', async () => {
    render(<Harness send={vi.fn()} journey={journey({ train: { headcode: '9Z98' } })} />);
    await userEvent.click(opener(REPLACE));

    await choose(EXAMPLE);

    expect(await screen.findByRole('button', { name: 'Replace train' })).toBeInTheDocument();
  });

  it('gives the page the file as parsed when the button is pressed', async () => {
    const send = vi.fn();
    render(<Harness send={send} />);
    await userEvent.click(opener());
    await choose(EXAMPLE);

    await userEvent.click(await screen.findByRole('button', { name: 'Add train' }));

    expect(send).toHaveBeenCalledExactlyOnceWith(readTrainFile(EXAMPLE).request);
  });

  it.each([
    ['is too big', () => ' '.repeat(200001), 'That file is too big to be a train file.'],
    ['is not JSON', () => '{"uid": ', 'That file isn’t JSON.'],
    [
      'is not a train file',
      () => '[]',
      'That file isn’t a train file. It needs a uid, a date, a headcode and at least two calls, each with a location.',
    ],
  ])(
    'says in one sentence that a file that %s, offers no button and empties the input',
    async (_, text, sentence) => {
      render(<Harness send={vi.fn()} />);
      await userEvent.click(opener());

      await choose(text());

      expect(await screen.findByRole('alert')).toHaveTextContent(sentence);
      expect(screen.queryByRole('button', { name: 'Add train' })).not.toBeInTheDocument();
      expect(screen.queryByRole('status')).not.toBeInTheDocument();
      expect(input().value).toBe('');
    },
  );

  it('says a file that cannot be read in one sentence, offers no button and empties the input', async () => {
    vi.stubGlobal(
      'FileReader',
      class {
        error = new Error('gone');
        readAsText() {
          this.onerror();
        }
      },
    );
    render(<Harness send={vi.fn()} />);
    await userEvent.click(opener());

    await choose(EXAMPLE);

    expect(await screen.findByRole('alert')).toHaveTextContent('That file couldn’t be read.');
    expect(screen.queryByRole('button', { name: 'Add train' })).not.toBeInTheDocument();
    expect(input().value).toBe('');
  });

  it('shows only the second of two files chosen in a row', async () => {
    render(<Harness send={vi.fn()} />);
    await userEvent.click(opener());
    await choose(EXAMPLE);
    await screen.findByRole('status');

    await choose('not json at all');

    expect(await screen.findByRole('alert')).toHaveTextContent('That file isn’t JSON.');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add train' })).not.toBeInTheDocument();
  });

  it('shows nothing when the file dialog is left with no file', async () => {
    render(<Harness send={vi.fn()} />);
    await userEvent.click(opener());
    await choose(EXAMPLE);
    await screen.findByRole('status');

    await userEvent.upload(input(), []);

    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows only the last of two files chosen while the first is still being read', async () => {
    const reads = [];
    vi.stubGlobal(
      'FileReader',
      class {
        readAsText(chosen) {
          reads.push({
            finish: (text) => {
              this.result = text;
              this.onload();
            },
            name: chosen.name,
          });
        }
      },
    );
    render(<Harness send={vi.fn()} />);
    await userEvent.click(opener());
    await choose('first', 'first.json');
    await choose('second', 'second.json');
    expect(reads.map((r) => r.name)).toEqual(['first.json', 'second.json']);

    await act(async () => reads[1].finish(EXAMPLE));
    expect(await screen.findByRole('status')).toHaveTextContent('Ready to send: train 9Z99');
    // The first one's read ends late, with a problem: nothing of it shows.
    await act(async () => reads[0].finish(''));

    expect(screen.getByRole('status')).toHaveTextContent('Ready to send: train 9Z99');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('forgets a chosen file when the panel is closed and opened again', async () => {
    render(<Harness send={vi.fn()} />);
    await userEvent.click(opener());
    await choose(EXAMPLE);
    await screen.findByRole('status');
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    await userEvent.click(opener());

    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(input().value).toBe('');
  });

  describe('closing', () => {
    it('closes on Cancel and puts focus back on the row’s button, sending nothing', async () => {
      const send = vi.fn();
      render(<Harness send={send} />);
      await userEvent.click(opener());
      await choose(EXAMPLE);

      await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }));

      expect(screen.queryByRole('group')).not.toBeInTheDocument();
      expect(opener()).toHaveFocus();
      expect(opener()).toHaveAttribute('aria-expanded', 'false');
      expect(send).not.toHaveBeenCalled();
    });

    it('closes on Escape from the input and puts focus back on the row’s button', async () => {
      render(<Harness send={vi.fn()} />);
      await userEvent.click(opener());
      expect(input()).toHaveFocus();

      await userEvent.keyboard('{Escape}');

      expect(screen.queryByRole('group')).not.toBeInTheDocument();
      expect(opener()).toHaveFocus();
    });

    it('closes on Escape from a button inside the panel', async () => {
      render(<Harness send={vi.fn()} />);
      await userEvent.click(opener());
      await choose(EXAMPLE);
      await screen.findByRole('button', { name: 'Add train' });
      await userEvent.tab();
      expect(screen.getByRole('button', { name: 'Add train' })).toHaveFocus();

      await userEvent.keyboard('{Escape}');

      expect(screen.queryByRole('group')).not.toBeInTheDocument();
    });

    it('ignores a key other than Escape', async () => {
      render(<Harness send={vi.fn()} />);
      await userEvent.click(opener());

      await userEvent.keyboard('{ArrowRight}');

      expect(group()).toBeInTheDocument();
      expect(input()).toHaveFocus();
    });

    it('leaves focus where the owner put it when the panel closes', async () => {
      render(<Harness send={vi.fn()} />);
      await userEvent.click(opener());

      await userEvent.click(screen.getByRole('button', { name: 'Elsewhere' }));

      expect(screen.queryByRole('group')).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Elsewhere' })).toHaveFocus();
    });
  });

  describe('while the request runs', () => {
    const running = async () => {
      const { gate, release } = held();
      const send = vi.fn(() => gate);
      render(<Harness send={send} />);
      await userEvent.click(opener());
      await choose(EXAMPLE);
      await userEvent.click(await screen.findByRole('button', { name: 'Add train' }));
      return { send, release };
    };

    it('reads Storing… and marks both buttons aria-disabled, not disabled', async () => {
      await running();

      const storing = screen.getByRole('button', { name: 'Storing…' });
      expect(storing).toHaveAttribute('aria-disabled', 'true');
      expect(storing).not.toBeDisabled();
      expect(screen.getByRole('button', { name: 'Cancel' })).toHaveAttribute(
        'aria-disabled',
        'true',
      );
    });

    it('sends nothing for a second press of Storing…', async () => {
      const { send } = await running();

      await userEvent.click(screen.getByRole('button', { name: 'Storing…' }));

      expect(send).toHaveBeenCalledTimes(1);
    });

    it('does nothing on Cancel and keeps the panel open', async () => {
      await running();

      await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

      expect(group()).toBeInTheDocument();
    });

    it('does nothing on Escape and keeps the panel open', async () => {
      await running();

      await userEvent.keyboard('{Escape}');

      expect(group()).toBeInTheDocument();
    });

    it('does nothing on the row’s button and keeps the panel open', async () => {
      await running();

      await userEvent.click(opener());

      expect(opener()).toHaveAttribute('aria-disabled', 'true');
      expect(group()).toBeInTheDocument();
    });

    it('ignores a file chosen meanwhile, empties the input and keeps what was shown', async () => {
      await running();

      await choose('not json at all');

      expect(input().value).toBe('');
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
      expect(screen.getByRole('status')).toHaveTextContent('Ready to send: train 9Z99');
    });

    it('is live again when the request has ended', async () => {
      const { release } = await running();

      await act(async () => release());

      expect(screen.getByRole('button', { name: 'Add train' })).not.toHaveAttribute(
        'aria-disabled',
      );
      expect(screen.getByRole('button', { name: 'Cancel' })).not.toHaveAttribute('aria-disabled');
    });
  });

  it('does not open while a request of another row runs', async () => {
    const onOpen = vi.fn();
    render(
      <JourneyTrain
        journey={journey()}
        open={false}
        busy
        problem={null}
        onOpen={onOpen}
        onClose={() => {}}
        onGive={() => {}}
        onEdit={() => {}}
      />,
    );

    expect(opener()).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(opener());

    expect(onOpen).not.toHaveBeenCalled();
  });

  it('shows a refusal as an alert in the open panel', () => {
    render(<Harness send={vi.fn()} problem="Another request was running. Try again." startOpen />);

    expect(screen.getByRole('alert')).toHaveTextContent('Another request was running. Try again.');
    expect(group()).toBeInTheDocument();
  });

  it('keeps the summary and both buttons live beside a refusal', async () => {
    let refuse;
    function Refusing() {
      const [problem, setProblem] = useState(null);
      refuse = setProblem;
      return (
        <JourneyTrain
          journey={journey()}
          open
          busy={false}
          problem={problem}
          onOpen={() => {}}
          onClose={() => {}}
          onGive={() => {}}
          onEdit={() => setProblem(null)}
        />
      );
    }
    render(<Refusing />);
    await choose(EXAMPLE);
    await screen.findByRole('status');

    act(() => refuse('That train did not run during this journey. Nothing was stored.'));

    expect(screen.getByRole('alert')).toHaveTextContent('That train did not run');
    expect(screen.getByRole('status')).toHaveTextContent('Ready to send: train 9Z99');
    expect(screen.getByRole('button', { name: 'Add train' })).not.toHaveAttribute('aria-disabled');
    expect(screen.getByRole('button', { name: 'Cancel' })).not.toHaveAttribute('aria-disabled');
  });

  it('clears a refusal when another file is chosen', async () => {
    render(<Harness send={vi.fn()} problem="Couldn’t store the train. Try again." startOpen />);

    await choose(EXAMPLE);

    await screen.findByRole('status');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows no alert when the page gives no refusal', () => {
    render(<Harness send={vi.fn()} startOpen />);

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  describe('when the journey’s train is taken away', () => {
    const TRAIN = { uid: 'X12345', date: '2030-03-04', headcode: '9Z99' };
    const shown = (train) => (
      <>
        <button type="button">Beside</button>
        <JourneyTrain
          journey={journey({ train })}
          open={false}
          busy={false}
          problem={null}
          onOpen={() => {}}
          onClose={() => {}}
          onGive={() => {}}
          onEdit={() => {}}
        />
      </>
    );

    it('puts focus on Add train file… when focus has fallen to the page', () => {
      const { rerender } = render(shown(TRAIN));
      expect(document.body).toHaveFocus();

      rerender(shown(null));

      expect(opener()).toHaveFocus();
    });

    it('leaves focus on another element where the owner put it', () => {
      const { rerender } = render(shown(TRAIN));
      screen.getByRole('button', { name: 'Beside' }).focus();

      rerender(shown(null));

      expect(screen.getByRole('button', { name: 'Beside' })).toHaveFocus();
    });

    it('moves no focus when the journey is given a train', () => {
      const { rerender } = render(shown(null));
      expect(document.body).toHaveFocus();

      rerender(shown(TRAIN));

      expect(document.body).toHaveFocus();
    });
  });
});
