import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import Header from './Header.jsx';

function Where() {
  const { pathname, search } = useLocation();
  return <output aria-label="Location">{`${pathname}${search}`}</output>;
}

const renderHeader = (path = '/') =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <Header />
      <Where />
    </MemoryRouter>,
  );

describe('Header', () => {
  it('has the wordmark, primary navigation and the settings icon', () => {
    renderHeader('/');
    expect(screen.getByRole('link', { name: 'CineFilm — Home' })).toHaveAttribute('href', '/');
    const nav = screen.getByRole('navigation', { name: 'Primary' });
    expect(nav).toHaveTextContent('Home');
    expect(screen.getByRole('link', { name: 'Home' })).toHaveAttribute('href', '/');
    expect(screen.getByRole('link', { name: 'Library' })).toHaveAttribute('href', '/library');
    expect(screen.getByRole('link', { name: 'Admin' })).toHaveAttribute('href', '/admin');
  });

  it('the wordmark link is one lockup: the 30px mark beside the CineFilm text', () => {
    renderHeader('/library');
    const home = screen.getByRole('link', { name: 'CineFilm — Home' });
    expect(home).toHaveTextContent('CineFilm');
    expect(within(home).getByTestId('logo-mark')).toHaveAttribute('width', '30');
  });

  it('marks the current section with aria-current', () => {
    renderHeader('/library');
    expect(screen.getByRole('link', { name: 'Library' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'Home' })).not.toHaveAttribute('aria-current');
  });

  it('with no index yet, Enter searches the library for the typed text and clears the box', async () => {
    renderHeader('/');
    const box = screen.getByRole('combobox', { name: 'Search the library' });
    await userEvent.type(box, 'blade runner{Enter}');
    expect(screen.getByLabelText('Location')).toHaveTextContent('/library?q=blade%20runner');
    expect(box).toHaveValue('');
  });

  it('trims the query before searching', async () => {
    renderHeader('/');
    await userEvent.type(screen.getByRole('combobox'), '  heat  {Enter}');
    expect(screen.getByLabelText('Location')).toHaveTextContent('/library?q=heat');
  });

  it('an empty search just opens the library', async () => {
    renderHeader('/');
    await userEvent.type(screen.getByRole('combobox'), '   {Enter}');
    expect(screen.getByLabelText('Location')).toHaveTextContent(/^\/library$/);
  });

  it('hides its search on the Library tabs, which have their own', () => {
    renderHeader('/library/collections');
    expect(screen.queryByRole('search')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Library' })).toHaveAttribute('aria-current', 'page');
  });

  it('hides its search on the Library page, which has its own', () => {
    renderHeader('/library');
    expect(screen.queryByRole('search')).not.toBeInTheDocument();
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
  });
});
