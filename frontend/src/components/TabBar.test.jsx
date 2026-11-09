import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import TabBar from './TabBar.jsx';

const renderAt = (path) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <TabBar />
    </MemoryRouter>,
  );

const currentTabs = () =>
  screen
    .getAllByRole('link')
    .filter((l) => l.getAttribute('aria-current') === 'page')
    .map((l) => l.textContent);

describe('TabBar', () => {
  it('is a Tabs navigation with Home, Library and Search', () => {
    renderAt('/');
    const nav = screen.getByRole('navigation', { name: 'Tabs' });
    expect(nav).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Home' })).toHaveAttribute('href', '/');
    expect(screen.getByRole('link', { name: 'Library' })).toHaveAttribute('href', '/library');
    expect(screen.getByRole('link', { name: 'Search' })).toHaveAttribute(
      'href',
      '/library?focus=search',
    );
  });

  it('marks Home as current on the home page', () => {
    renderAt('/');
    expect(currentTabs()).toEqual(['Home']);
  });

  it('marks Library as current on the library', () => {
    renderAt('/library');
    expect(currentTabs()).toEqual(['Library']);
  });

  it('marks Library as current on the Collections tab too', () => {
    renderAt('/library/collections');
    expect(currentTabs()).toEqual(['Library']);
    expect(screen.getByRole('link', { name: 'Library' })).toHaveAttribute('aria-current', 'page');
  });

  it('marks Search, not Library, as current when the library was opened to search', () => {
    renderAt('/library?focus=search');
    expect(currentTabs()).toEqual(['Search']);
  });

  it('marks nothing as current on other pages', () => {
    renderAt('/films/1');
    expect(currentTabs()).toEqual([]);
  });
});
