import { Outlet, useLocation } from 'react-router-dom';
import { isSheetOpen } from '../state/searchSheet.js';
import Header from './Header.jsx';
import SearchSheet from './SearchSheet.jsx';
import TabBar from './TabBar.jsx';
import { useSearchIndexLifecycle } from './useSearchIndexLifecycle.js';
import { useSlashFocus } from './useSlashFocus.js';

export default function Layout() {
  const { search } = useLocation();
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
      {isSheetOpen(search) ? <SearchSheet /> : null}
    </div>
  );
}
