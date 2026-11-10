import { useState } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom';
import { SearchIcon, SettingsIcon } from './icons.jsx';
import LogoMark from './LogoMark.jsx';

export default function Header() {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const [query, setQuery] = useState('');
  const showSearch = !pathname.startsWith('/library');

  const submit = (event) => {
    event.preventDefault();
    const q = query.trim();
    navigate(q ? `/library?q=${encodeURIComponent(q)}` : '/library');
    setQuery('');
  };

  return (
    <header className="header">
      <Link to="/" className="header__wordmark" aria-label="CineFilm — Home">
        <LogoMark size={30} />
        <span aria-hidden="true">CineFilm</span>
      </Link>
      <nav className="header__nav" aria-label="Primary">
        <NavLink to="/" end className="header__link">
          Home
        </NavLink>
        <NavLink to="/library" className="header__link">
          Library
        </NavLink>
      </nav>
      <div className="header__spacer" />
      {showSearch ? (
        <form className="header__search" role="search" onSubmit={submit}>
          <label className="searchbox">
            <SearchIcon size={16} />
            <input
              type="search"
              className="searchbox__input"
              placeholder="Title, actor or director"
              aria-label="Search the library"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              data-global-search
            />
            <kbd className="searchbox__hint" aria-hidden="true">
              /
            </kbd>
          </label>
        </form>
      ) : null}
      <Link to="/admin" className="header__settings" aria-label="Settings">
        <SettingsIcon />
      </Link>
    </header>
  );
}
