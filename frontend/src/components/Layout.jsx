import { Outlet } from 'react-router-dom';
import Header from './Header.jsx';
import TabBar from './TabBar.jsx';
import { useSearchIndexLifecycle } from './useSearchIndexLifecycle.js';
import { useSlashFocus } from './useSlashFocus.js';

export default function Layout() {
  useSlashFocus();
  // Warm the shared search index so it is ready (and revalidating) before anything renders it.
  useSearchIndexLifecycle();
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
