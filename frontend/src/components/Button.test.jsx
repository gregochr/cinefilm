import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { Button, ButtonLink } from './Button.jsx';

describe('Button', () => {
  it('is a type=button so it never submits a surrounding form by accident', async () => {
    const onSubmit = vi.fn((e) => e.preventDefault());
    render(
      <form onSubmit={onSubmit}>
        <Button>Plain</Button>
      </form>,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Plain' }));
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('can be a submit button', async () => {
    const onSubmit = vi.fn((e) => e.preventDefault());
    render(
      <form onSubmit={onSubmit}>
        <Button type="submit">Go</Button>
      </form>,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Go' }));
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('is secondary by default and takes a variant, size and extra class', () => {
    const { rerender } = render(<Button>A</Button>);
    expect(screen.getByRole('button')).toHaveClass('btn', 'btn--secondary');
    rerender(
      <Button variant="primary" size="lg" className="extra">
        A
      </Button>,
    );
    expect(screen.getByRole('button')).toHaveClass('btn--primary', 'btn--lg', 'extra');
  });

  it('does not fire when disabled', async () => {
    const onClick = vi.fn();
    render(
      <Button disabled onClick={onClick}>
        A
      </Button>,
    );
    await userEvent.click(screen.getByRole('button'));
    expect(onClick).not.toHaveBeenCalled();
  });
});

describe('ButtonLink', () => {
  it('is a link styled as a button', () => {
    render(
      <MemoryRouter>
        <ButtonLink variant="primary" size="xl" to="/admin" className="x">
          Open
        </ButtonLink>
      </MemoryRouter>,
    );
    const link = screen.getByRole('link', { name: 'Open' });
    expect(link).toHaveAttribute('href', '/admin');
    expect(link).toHaveClass('btn', 'btn--primary', 'btn--xl', 'x');
  });

  it('defaults to the secondary style', () => {
    render(
      <MemoryRouter>
        <ButtonLink to="/">Home</ButtonLink>
      </MemoryRouter>,
    );
    expect(screen.getByRole('link')).toHaveClass('btn--secondary');
  });
});
