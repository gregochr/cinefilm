import { Outlet } from 'react-router-dom';
import Header from './Header.jsx';
import TabBar from './TabBar.jsx';
import { useSlashFocus } from './useSlashFocus.js';

export default function Layout() {
  useSlashFocus();
  return (
    <div className="app">
      <Header />
      <main className="page">
        <Outlet />
      </main>
      <TabBar />
    </div>
  );
}
