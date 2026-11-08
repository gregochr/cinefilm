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

describe('QualityMenu', () => {
  const setup = (props = {}) => {
    const onSelect = vi.fn();
    render(
      <QualityMenu
        qualities={QUALITIES}
        value="original"
        height={1080}
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
