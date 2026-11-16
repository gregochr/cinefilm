import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { AudioSubtitlesPanel, QualityMenu } from './PlayerMenus.jsx';

const QUALITIES = [
  { id: 'auto', available: true },
  { id: 'original', available: true, bitrate: 18_400_000 },
  { id: '1080p', available: false, bitrate: 8_000_000 },
  { id: '720p', available: true, bitrate: 4_000_000, premade: true },
];

describe('QualityMenu: transcoding', () => {
  const OFFLINE = [
    { id: 'auto', available: false },
    { id: 'original', available: true, bitrate: 18_400_000 },
    { id: '360p', available: false, bitrate: 600_000 },
  ];

  it('names the rung Auto is using now', () => {
    render(<QualityMenu qualities={QUALITIES} value="auto" activeRung="720p" onSelect={vi.fn()} />);
    expect(screen.getByRole('menuitemradio', { name: /Auto/ })).toHaveTextContent('now 720p');
  });

  it('lists 360p like any other rung', () => {
    render(
      <QualityMenu
        qualities={[...QUALITIES, { id: '360p', available: true, bitrate: 600_000 }]}
        value="360p"
        onSelect={vi.fn()}
      />,
    );
    const row = screen.getByRole('menuitemradio', { name: /360p/ });
    expect(row).toHaveAttribute('aria-checked', 'true');
    expect(row).toHaveTextContent('0.6 Mbps');
  });

  it('says why when the worker is offline: Auto and lower are disabled, Original is not', () => {
    render(<QualityMenu qualities={OFFLINE} value="original" onSelect={vi.fn()} />);
    expect(screen.getByRole('menuitemradio', { name: /Auto/ })).toBeDisabled();
    expect(screen.getByRole('menuitemradio', { name: /360p/ })).toBeDisabled();
    expect(screen.getByRole('menuitemradio', { name: /^Original/ })).toBeEnabled();
    expect(screen.getByText(/Lower qualities unavailable right now/)).toHaveTextContent(
      'Lower qualities unavailable right now — the transcode worker is offline. Pre-made versions still play.',
    );
    expect(screen.getByRole('menu', { name: 'Quality' })).not.toHaveTextContent('offline');
  });

  it('has no offline footer while Auto is available, even with some rungs missing', () => {
    render(<QualityMenu qualities={QUALITIES} value="auto" onSelect={vi.fn()} />);
    expect(screen.getByRole('menu', { name: 'Quality' })).not.toHaveTextContent('offline');
  });
});

describe('QualityMenu', () => {
  const setup = (props = {}) => {
    const onSelect = vi.fn();
    render(
      <QualityMenu
        qualities={QUALITIES}
        value="original"
        width={1920}
        onSelect={onSelect}
        {...props}
      />,
    );
    return { onSelect, menu: screen.getByRole('menu', { name: 'Quality' }) };
  };

  it('is a menu of menuitemradio rows with aria-checked on the current one', () => {
    const { menu } = setup();
    expect(within(menu).getAllByRole('menuitemradio')).toHaveLength(4);
    expect(within(menu).getByRole('menuitemradio', { name: /^Original/ })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(within(menu).getByRole('menuitemradio', { name: /Auto/ })).toHaveAttribute(
      'aria-checked',
      'false',
    );
  });

  it('shows the detail for each row and disables unavailable qualities', () => {
    const { menu } = setup();
    expect(within(menu).getByText('now Original')).toBeInTheDocument();
    expect(within(menu).getByText('1080p · 18.4 Mbps')).toBeInTheDocument();
    expect(within(menu).getByText('4 Mbps · pre-made')).toBeInTheDocument();
    expect(within(menu).getByRole('menuitemradio', { name: /^1080p/ })).toBeDisabled();
  });

  it('moves focus to the checked row when it opens', () => {
    setup();
    expect(screen.getByRole('menuitemradio', { name: /^Original/ })).toHaveFocus();
  });

  it('falls back to the first enabled row when nothing is checked', () => {
    setup({ value: 'none' });
    expect(screen.getByRole('menuitemradio', { name: /Auto/ })).toHaveFocus();
  });

  it('reports the selected quality', async () => {
    const { onSelect } = setup();
    await userEvent.click(screen.getByRole('menuitemradio', { name: /720p/ }));
    expect(onSelect).toHaveBeenCalledWith('720p');
  });

  it('arrow keys move through enabled rows only, wrapping at both ends', async () => {
    setup({ value: 'auto' });
    expect(screen.getByRole('menuitemradio', { name: /Auto/ })).toHaveFocus();
    await userEvent.keyboard('{ArrowDown}');
    expect(screen.getByRole('menuitemradio', { name: /^Original/ })).toHaveFocus();
    await userEvent.keyboard('{ArrowDown}'); // skips the disabled 1080p
    expect(screen.getByRole('menuitemradio', { name: /720p/ })).toHaveFocus();
    await userEvent.keyboard('{ArrowDown}');
    expect(screen.getByRole('menuitemradio', { name: /Auto/ })).toHaveFocus();
    await userEvent.keyboard('{ArrowUp}');
    expect(screen.getByRole('menuitemradio', { name: /720p/ })).toHaveFocus();
  });

  it('other keys do not move focus', async () => {
    setup();
    await userEvent.keyboard('x');
    expect(screen.getByRole('menuitemradio', { name: /^Original/ })).toHaveFocus();
  });

  it('with every row disabled, arrows leave focus on the menu', async () => {
    setup({ qualities: QUALITIES.map((q) => ({ ...q, available: false })) });
    const menu = screen.getByRole('menu', { name: 'Quality' });
    menu.focus();
    await userEvent.keyboard('{ArrowDown}');
    expect(menu).toHaveFocus();
  });
});

describe('AudioSubtitlesPanel', () => {
  const AUDIO = [
    { id: 1, language: 'English', channels: '5.1', codec: 'DTS' },
    { id: 2, title: 'Commentary', codec: 'AAC' },
  ];
  const SUBS = [
    { id: 5, language: 'English' },
    { id: 6, language: 'English', forced: true },
  ];
  const setup = (props = {}) => {
    const handlers = { onAudio: vi.fn(), onSubtitle: vi.fn() };
    render(
      <AudioSubtitlesPanel
        audioStreams={AUDIO}
        audioValue={1}
        subtitleStreams={SUBS}
        subtitleValue={6}
        {...handlers}
        {...props}
      />,
    );
    return handlers;
  };

  it('is a group holding an Audio menu and a Subtitles menu', () => {
    setup();
    const panel = screen.getByRole('group', { name: 'Audio and subtitles' });
    expect(within(panel).getByRole('menu', { name: 'Audio' })).toBeInTheDocument();
    expect(within(panel).getByRole('menu', { name: 'Subtitles' })).toBeInTheDocument();
  });

  it('marks the current audio track and subtitle with aria-checked', () => {
    setup();
    const audio = screen.getByRole('menu', { name: 'Audio' });
    expect(within(audio).getByRole('menuitemradio', { name: /English 5.1/ })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(within(audio).getByRole('menuitemradio', { name: /Commentary/ })).toHaveAttribute(
      'aria-checked',
      'false',
    );
    const subs = screen.getByRole('menu', { name: 'Subtitles' });
    expect(within(subs).getByRole('menuitemradio', { name: /English \(forced\)/ })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(within(subs).getByRole('menuitemradio', { name: /^Off/ })).toHaveAttribute(
      'aria-checked',
      'false',
    );
  });

  it('lists Off first with its forced-if-needed note', () => {
    setup();
    const rows = within(screen.getByRole('menu', { name: 'Subtitles' })).getAllByRole(
      'menuitemradio',
    );
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent('Off');
    expect(rows[0]).toHaveTextContent('forced if needed');
  });

  it('reports audio and subtitle choices by stream key', async () => {
    const { onAudio, onSubtitle } = setup();
    await userEvent.click(screen.getByRole('menuitemradio', { name: /Commentary/ }));
    expect(onAudio).toHaveBeenCalledWith(2);
    await userEvent.click(screen.getByRole('menuitemradio', { name: /^Off/ }));
    expect(onSubtitle).toHaveBeenLastCalledWith('off');
    const subs = screen.getByRole('menu', { name: 'Subtitles' });
    await userEvent.click(within(subs).getAllByRole('menuitemradio')[1]);
    expect(onSubtitle).toHaveBeenLastCalledWith(5);
  });

  it('says so when the film has no subtitles', () => {
    setup({ subtitleStreams: [], subtitleValue: 'off' });
    const subs = screen.getByRole('menu', { name: 'Subtitles' });
    expect(within(subs).getByText('None for this film')).toBeInTheDocument();
    expect(within(subs).queryByRole('menuitemradio')).not.toBeInTheDocument();
  });

  it('opens with focus on the checked audio track and arrows stay inside one column', async () => {
    setup();
    expect(screen.getByRole('menuitemradio', { name: /English 5.1/ })).toHaveFocus();
    await userEvent.keyboard('{ArrowDown}');
    expect(screen.getByRole('menuitemradio', { name: /Commentary/ })).toHaveFocus();
    await userEvent.keyboard('{ArrowDown}');
    expect(screen.getByRole('menuitemradio', { name: /English 5.1/ })).toHaveFocus();
  });
});

describe('AudioSubtitlesPanel: Subtitles are wrong', () => {
  const props = {
    audioStreams: [{ id: 1, language: 'English', channels: '5.1', codec: 'DTS' }],
    audioValue: 1,
    subtitleStreams: [{ id: 5, language: 'English' }],
    hasFullSubtitle: true,
    subtitleValue: 'full',
    onAudio: vi.fn(),
    onSubtitle: vi.fn(),
  };
  const wrongOf = (over = {}) => ({
    available: true,
    busy: false,
    problem: null,
    onWrong: vi.fn(),
    ...over,
  });
  const subs = () => screen.getByRole('menu', { name: 'Subtitles' });
  const action = () => within(subs()).getByRole('menuitem', { name: 'Subtitles are wrong' });

  it('is not there unless a downloaded subtitle shows', () => {
    const { rerender } = render(<AudioSubtitlesPanel {...props} />);
    expect(screen.queryByRole('menuitem', { name: 'Subtitles are wrong' })).not.toBeInTheDocument();

    rerender(<AudioSubtitlesPanel {...props} wrong={wrongOf({ available: false })} />);
    expect(screen.queryByRole('menuitem', { name: 'Subtitles are wrong' })).not.toBeInTheDocument();
  });

  it('is a plain action under the subtitle choices, not a choice, and asks once per click', async () => {
    const wrong = wrongOf();
    render(<AudioSubtitlesPanel {...props} wrong={wrong} />);

    expect(within(subs()).getAllByRole('menuitemradio')).toHaveLength(3);
    await userEvent.click(action());

    expect(wrong.onWrong).toHaveBeenCalledTimes(1);
  });

  it('says it is removing and ignores clicks while the request runs, keeping focus on it', async () => {
    const wrong = wrongOf({ busy: true });
    render(<AudioSubtitlesPanel {...props} wrong={wrong} />);

    const busy = within(subs()).getByRole('menuitem', { name: 'Removing…' });
    expect(busy).toHaveAttribute('aria-disabled', 'true');
    busy.focus();
    await userEvent.click(busy);

    expect(wrong.onWrong).not.toHaveBeenCalled();
    expect(busy).toHaveFocus();
  });

  it('shows why it failed', () => {
    const problem = 'Couldn’t remove the download. Try again.';
    render(<AudioSubtitlesPanel {...props} wrong={wrongOf({ problem })} />);

    expect(screen.getByRole('alert')).toHaveTextContent(problem);
    expect(within(subs()).queryByRole('alert')).not.toBeInTheDocument();
  });

  it('arrow keys reach it from the last subtitle choice', async () => {
    render(<AudioSubtitlesPanel {...props} wrong={wrongOf()} />);
    within(subs()).getAllByRole('menuitemradio')[2].focus();

    await userEvent.keyboard('{ArrowDown}');

    expect(action()).toHaveFocus();
  });

  it('moves focus to the checked subtitle when the action goes away', () => {
    const { rerender } = render(<AudioSubtitlesPanel {...props} wrong={wrongOf()} />);
    action().focus();

    rerender(
      <AudioSubtitlesPanel {...props} subtitleValue="off" wrong={wrongOf({ available: false })} />,
    );

    expect(within(subs()).getByRole('menuitemradio', { name: /^Off/ })).toHaveFocus();
  });

  it('lands on the first subtitle choice when none is checked', () => {
    const { rerender } = render(
      <AudioSubtitlesPanel {...props} subtitleValue="nothing" wrong={wrongOf()} />,
    );
    action().focus();

    rerender(
      <AudioSubtitlesPanel
        {...props}
        subtitleValue="nothing"
        wrong={wrongOf({ available: false })}
      />,
    );

    expect(within(subs()).getAllByRole('menuitemradio')[0]).toHaveFocus();
  });

  it('leaves focus alone when the action goes away while the viewer is elsewhere in the panel', () => {
    const { rerender } = render(<AudioSubtitlesPanel {...props} wrong={wrongOf()} />);
    const audio = screen.getByRole('menuitemradio', { name: /English 5.1/ });
    audio.focus();

    rerender(
      <AudioSubtitlesPanel {...props} subtitleValue="off" wrong={wrongOf({ available: false })} />,
    );

    expect(audio).toHaveFocus();
  });

  it('leaves focus alone when it is outside the panel, such as on a control-bar button', () => {
    const tree = (over) => (
      <>
        <button type="button">Control bar</button>
        <AudioSubtitlesPanel {...props} wrong={wrongOf(over)} />
      </>
    );
    const { rerender } = render(tree({}));
    const outside = screen.getByRole('button', { name: 'Control bar' });
    outside.focus();

    rerender(tree({ available: false }));

    expect(outside).toHaveFocus();
  });
});

describe('AudioSubtitlesPanel: subtitle timing', () => {
  const props = {
    audioStreams: [{ id: 1, language: 'English', channels: '5.1', codec: 'DTS' }],
    audioValue: 1,
    subtitleStreams: [{ id: 5, language: 'English' }],
    hasFullSubtitle: true,
    subtitleValue: 'full',
    onAudio: vi.fn(),
    onSubtitle: vi.fn(),
  };
  const timingOf = (over = {}) => ({
    available: true,
    offsets: [500],
    atZero: false,
    busy: false,
    loading: false,
    frozen: false,
    problem: null,
    onEarlier: vi.fn(),
    onLater: vi.fn(),
    onReset: vi.fn(),
    ...over,
  });
  const subs = () => screen.getByRole('menu', { name: 'Subtitles' });
  const earlier = () =>
    within(subs()).getByRole('menuitem', { name: 'Show lines 0.25 seconds earlier' });
  const later = () =>
    within(subs()).getByRole('menuitem', { name: 'Show lines 0.25 seconds later' });
  const reset = () => within(subs()).getByRole('menuitem', { name: 'Reset timing' });

  it('is not there unless a subtitle file shows', () => {
    const { rerender } = render(<AudioSubtitlesPanel {...props} />);
    expect(screen.queryByRole('group', { name: 'Subtitle timing' })).not.toBeInTheDocument();

    rerender(<AudioSubtitlesPanel {...props} timing={timingOf({ available: false })} />);
    expect(screen.queryByRole('group', { name: 'Subtitle timing' })).not.toBeInTheDocument();
  });

  it('is three plain actions in the Subtitles menu, not choices, showing the offset', () => {
    render(<AudioSubtitlesPanel {...props} timing={timingOf()} />);

    expect(within(subs()).getAllByRole('menuitemradio')).toHaveLength(3);
    expect(within(subs()).getByRole('group', { name: 'Subtitle timing' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Timing: +0.5 s');
    expect(earlier()).toHaveTextContent('Earlier');
    expect(later()).toHaveTextContent('Later');
  });

  it('keeps the readout and the problem outside the menus, so a menu holds only items', () => {
    render(
      <AudioSubtitlesPanel
        {...props}
        timing={timingOf({ problem: 'Couldn’t change the timing. Try again.' })}
        wrong={{ available: true, busy: false, problem: 'Couldn’t remove.', onWrong: vi.fn() }}
      />,
    );

    expect(screen.getAllByRole('alert')).toHaveLength(2);
    expect(screen.getByRole('status')).toHaveTextContent('Timing: +0.5 s');
    for (const menu of screen.getAllByRole('menu')) {
      expect(within(menu).queryByRole('status')).not.toBeInTheDocument();
      expect(within(menu).queryByRole('alert')).not.toBeInTheDocument();
      expect(within(menu).queryByRole('heading')).not.toBeInTheDocument();
      expect(menu).not.toHaveTextContent(/Timing:|Couldn’t/);
    }
  });

  it('says No timing change at 0, and that parts differ when they do', () => {
    const { rerender } = render(
      <AudioSubtitlesPanel {...props} timing={timingOf({ offsets: [0] })} />,
    );
    expect(screen.getByRole('status')).toHaveTextContent('Timing: No timing change');

    rerender(<AudioSubtitlesPanel {...props} timing={timingOf({ offsets: [250, 500] })} />);
    expect(screen.getByRole('status')).toHaveTextContent('Timing: differs by part');
  });

  it('keeps the readout region (empty) while the offset is not known, so the first one is announced', () => {
    render(<AudioSubtitlesPanel {...props} timing={timingOf({ offsets: null })} />);

    expect(screen.getByRole('status')).toBeEmptyDOMElement();
  });

  it('asks once per click for each action', async () => {
    const timing = timingOf();
    render(<AudioSubtitlesPanel {...props} timing={timing} />);

    await userEvent.click(earlier());
    await userEvent.click(later());
    await userEvent.click(reset());

    expect(timing.onEarlier).toHaveBeenCalledTimes(1);
    expect(timing.onLater).toHaveBeenCalledTimes(1);
    expect(timing.onReset).toHaveBeenCalledTimes(1);
  });

  it('while saving, says so, does nothing on a click and keeps focus on the pressed button', async () => {
    const timing = timingOf({ busy: true });
    render(<AudioSubtitlesPanel {...props} timing={timing} />);

    later().focus();
    await userEvent.click(later());

    expect(later()).toHaveAttribute('aria-disabled', 'true');
    expect(earlier()).toHaveAttribute('aria-disabled', 'true');
    expect(reset()).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByRole('status')).toHaveTextContent('Saving…');
    expect(later()).toHaveFocus();
    expect(timing.onLater).not.toHaveBeenCalled();
  });

  it('disables Later at the +10 minute limit and Earlier at -10, each only that way, and still focusable', async () => {
    const top = timingOf({ offsets: [600000], atLatest: true });
    const { unmount } = render(<AudioSubtitlesPanel {...props} timing={top} />);

    later().focus();
    await userEvent.click(later());
    expect(later()).toHaveAttribute('aria-disabled', 'true');
    expect(later()).toHaveFocus();
    expect(earlier()).not.toHaveAttribute('aria-disabled');
    expect(top.onLater).not.toHaveBeenCalled();
    unmount();

    const bottom = timingOf({ offsets: [-600000], atEarliest: true });
    render(<AudioSubtitlesPanel {...props} timing={bottom} />);
    await userEvent.click(earlier());
    expect(earlier()).toHaveAttribute('aria-disabled', 'true');
    expect(later()).not.toHaveAttribute('aria-disabled');
    expect(bottom.onEarlier).not.toHaveBeenCalled();
  });

  it('has nothing to reset when the timing is already 0, but Earlier and Later still work', async () => {
    const timing = timingOf({ offsets: [0], atZero: true });
    render(<AudioSubtitlesPanel {...props} timing={timing} />);

    await userEvent.click(reset());
    await userEvent.click(later());

    expect(reset()).toHaveAttribute('aria-disabled', 'true');
    expect(timing.onReset).not.toHaveBeenCalled();
    expect(timing.onLater).toHaveBeenCalledTimes(1);
  });

  it('says Saving… while the new timing loads, but the buttons still work', async () => {
    const timing = timingOf({ loading: true });
    render(<AudioSubtitlesPanel {...props} timing={timing} />);

    expect(screen.getByRole('status')).toHaveTextContent('Saving…');
    await userEvent.click(later());

    expect(timing.onLater).toHaveBeenCalledTimes(1);
  });

  it('is disabled for good once the file has changed under the viewer (frozen)', async () => {
    const timing = timingOf({ frozen: true, offsets: [250] });
    render(<AudioSubtitlesPanel {...props} timing={timing} />);

    await userEvent.click(earlier());
    await userEvent.click(later());
    await userEvent.click(reset());

    for (const button of [earlier(), later(), reset()]) {
      expect(button).toHaveAttribute('aria-disabled', 'true');
    }
    expect(screen.getByRole('status')).toHaveTextContent('Timing: +0.25 s');
    expect(timing.onEarlier).not.toHaveBeenCalled();
    expect(timing.onLater).not.toHaveBeenCalled();
    expect(timing.onReset).not.toHaveBeenCalled();
  });

  it('shows a problem as an alert line', () => {
    render(
      <AudioSubtitlesPanel
        {...props}
        timing={timingOf({ problem: 'Couldn’t change the timing. Try again.' })}
      />,
    );

    expect(screen.getByRole('alert')).toHaveTextContent('Couldn’t change the timing. Try again.');
  });

  it('ArrowDown and ArrowUp go on from a focused item that is disabled for now, not back to the first', async () => {
    render(<AudioSubtitlesPanel {...props} timing={timingOf({ busy: true })} />);
    const choices = within(subs()).getAllByRole('menuitemradio');
    later().focus();

    await userEvent.keyboard('{ArrowDown}');
    // Earlier, Later and Reset are all disabled while saving: the next usable item after them is the first choice.
    expect(choices[0]).toHaveFocus();
    later().focus();
    await userEvent.keyboard('{ArrowUp}');
    expect(choices[choices.length - 1]).toHaveFocus();
  });

  it('goes on from the position of a focused disabled item, in the middle of the menu', async () => {
    render(<AudioSubtitlesPanel {...props} timing={timingOf({ offsets: [0], atZero: true })} />);
    const choices = within(subs()).getAllByRole('menuitemradio');
    reset().focus();

    await userEvent.keyboard('{ArrowUp}');
    expect(later()).toHaveFocus();
    reset().focus();
    await userEvent.keyboard('{ArrowDown}');
    expect(choices[0]).toHaveFocus();
  });

  it('from outside the items ArrowDown starts at the first and ArrowUp at the last', async () => {
    render(<AudioSubtitlesPanel {...props} timing={timingOf()} />);
    const choices = within(subs()).getAllByRole('menuitemradio');
    subs().focus();

    await userEvent.keyboard('{ArrowUp}');
    expect(reset()).toHaveFocus();
    subs().focus();
    await userEvent.keyboard('{ArrowDown}');
    expect(choices[0]).toHaveFocus();
  });

  it('does nothing when no item is usable', async () => {
    render(
      <AudioSubtitlesPanel
        {...props}
        audioStreams={[]}
        subtitleStreams={[]}
        hasFullSubtitle={false}
        timing={timingOf({ busy: true })}
      />,
    );
    later().focus();

    await userEvent.keyboard('{ArrowDown}');

    expect(later()).toHaveFocus();
  });

  it('is reachable with the arrow keys, after the subtitle choices', async () => {
    render(<AudioSubtitlesPanel {...props} timing={timingOf()} />);
    const choices = within(subs()).getAllByRole('menuitemradio');
    choices[choices.length - 1].focus();

    await userEvent.keyboard('{ArrowDown}');
    expect(earlier()).toHaveFocus();
    await userEvent.keyboard('{ArrowDown}');
    expect(later()).toHaveFocus();
    await userEvent.keyboard('{ArrowDown}');
    expect(reset()).toHaveFocus();
    await userEvent.keyboard('{ArrowDown}');
    expect(choices[0]).toHaveFocus();
  });
});
