import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import SignInCard from './SignInCard.jsx';

const setup = (props = {}) => {
  const onSignIn = vi.fn().mockResolvedValue(true);
  render(<SignInCard onSignIn={onSignIn} {...props} />);
  return { onSignIn };
};

describe('SignInCard', () => {
  it('is a modal dialog titled "Signed out" with the handover copy', () => {
    setup();
    const dialog = screen.getByRole('dialog', { name: 'Signed out' });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveTextContent(
      'You’ve been signed out while you were away. Sign in again and you’ll be back where you were.',
    );
  });

  it('focuses the password field when it appears', () => {
    setup();
    expect(screen.getByLabelText('Password')).toHaveFocus();
  });

  it('keeps Sign in disabled until a password is typed', async () => {
    setup();
    const button = screen.getByRole('button', { name: 'Sign in' });
    expect(button).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Password'), 'x');
    expect(button).toBeEnabled();
  });

  it('keeps Sign in disabled while a sign-in is in flight', async () => {
    setup({ submitting: true });
    await userEvent.type(screen.getByLabelText('Password'), 'x');
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeDisabled();
  });

  it('submits the typed password on Enter and then empties the field', async () => {
    const { onSignIn } = setup();
    const field = screen.getByLabelText('Password');
    await userEvent.type(field, 'changeme{Enter}');
    expect(onSignIn).toHaveBeenCalledWith('changeme');
    await vi.waitFor(() => expect(field).toHaveValue(''));
  });

  it('keeps the password when the sign-in fails so it can be corrected', async () => {
    const onSignIn = vi.fn().mockResolvedValue(false);
    render(<SignInCard onSignIn={onSignIn} error="That password didn't work. Try again." />);
    const field = screen.getByLabelText('Password');
    await userEvent.type(field, 'wrong');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(onSignIn).toHaveBeenCalledWith('wrong');
    expect(field).toHaveValue('wrong');
  });

  it('announces an error as an alert and shows none otherwise', () => {
    const { unmount } = render(
      <SignInCard onSignIn={vi.fn()} error="That password didn't work. Try again." />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent("That password didn't work. Try again.");
    unmount();
    render(<SignInCard onSignIn={vi.fn()} error={null} />);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
