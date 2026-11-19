import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import ChristmasToast from './ChristmasToast.jsx';

const region = () => screen.getByRole('status', { name: 'Christmas notification' });
const undo = { tmdbId: 106, title: 'Die Hard', choice: null, kind: 'leave' };
const handlers = () => ({ onUndo: vi.fn(), onHold: vi.fn(), onRelease: vi.fn() });

describe('ChristmasToast', () => {
  it('keeps an empty live region when there is no toast, so a new message is announced', () => {
    render(<ChristmasToast toast={null} {...handlers()} />);
    expect(region()).toBeEmptyDOMElement();
  });

  it('shows the message with an Undo button, which hands back what to undo', async () => {
    const props = handlers();
    render(
      <ChristmasToast
        toast={{ message: "Left out Die Hard. It won't show at Christmas.", undo }}
        {...props}
      />,
    );
    expect(region()).toHaveTextContent("Left out Die Hard. It won't show at Christmas.");
    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(props.onUndo).toHaveBeenCalledTimes(1);
    expect(props.onUndo).toHaveBeenCalledWith(undo);
  });

  it('shows a message with no Undo button when there is nothing to undo', () => {
    render(<ChristmasToast toast={{ message: 'A scan is running', undo: null }} {...handlers()} />);
    expect(region()).toHaveTextContent('A scan is running');
    expect(screen.queryByRole('button', { name: 'Undo' })).not.toBeInTheDocument();
  });

  it('replaces the message when a new one arrives', () => {
    const props = handlers();
    const { rerender } = render(
      <ChristmasToast toast={{ message: 'Left out Die Hard.', undo }} {...props} />,
    );
    rerender(<ChristmasToast toast={{ message: 'Removed Gremlins.', undo }} {...props} />);
    expect(region()).toHaveTextContent('Removed Gremlins.');
    expect(region()).not.toHaveTextContent('Die Hard');
  });

  it('re-creates the pill for an identical repeated message when its id is new', async () => {
    const props = handlers();
    const toast = { message: 'Left out Die Hard.', undo };
    const { rerender } = render(<ChristmasToast toast={{ ...toast, id: 1 }} {...props} />);
    await userEvent.tab();
    expect(screen.getByRole('button', { name: 'Undo' })).toHaveFocus();
    rerender(<ChristmasToast toast={{ ...toast, id: 2 }} {...props} />);
    expect(screen.getByRole('button', { name: 'Undo' })).not.toHaveFocus();
  });

  it('hands the Undo button to the ref it is given', () => {
    const undoRef = { current: null };
    render(
      <ChristmasToast
        toast={{ message: 'Left out Die Hard.', undo, id: 1 }}
        undoRef={undoRef}
        {...handlers()}
      />,
    );
    expect(undoRef.current).toBe(screen.getByRole('button', { name: 'Undo' }));
  });

  it('holds while the pointer is over it and lets go when it leaves', async () => {
    const props = handlers();
    render(<ChristmasToast toast={{ message: 'Left out Die Hard.', undo }} {...props} />);
    await userEvent.hover(screen.getByText('Left out Die Hard.'));
    expect(props.onHold).toHaveBeenCalledTimes(1);
    expect(props.onRelease).not.toHaveBeenCalled();
    await userEvent.unhover(screen.getByText('Left out Die Hard.'));
    expect(props.onRelease).toHaveBeenCalledTimes(1);
  });

  it('holds while focus is inside it, and lets go only when neither pointer nor focus is on it', async () => {
    const props = handlers();
    render(<ChristmasToast toast={{ message: 'Left out Die Hard.', undo }} {...props} />);
    await userEvent.tab();
    expect(screen.getByRole('button', { name: 'Undo' })).toHaveFocus();
    expect(props.onHold).toHaveBeenCalledTimes(1);

    await userEvent.hover(screen.getByText('Left out Die Hard.'));
    await userEvent.unhover(screen.getByText('Left out Die Hard.'));
    expect(props.onHold).toHaveBeenCalledTimes(1);
    expect(props.onRelease).not.toHaveBeenCalled();

    await userEvent.tab();
    expect(props.onRelease).toHaveBeenCalledTimes(1);
  });
});
