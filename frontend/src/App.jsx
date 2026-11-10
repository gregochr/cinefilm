import { Route, Routes } from 'react-router-dom';
import Layout from './components/Layout.jsx';
import LogoMark from './components/LogoMark.jsx';
import SignInCard from './components/SignInCard.jsx';
import Admin from './screens/Admin.jsx';
import Collection from './screens/Collection.jsx';
import FilmDetail from './screens/FilmDetail.jsx';
import Home from './screens/Home.jsx';
import Library from './screens/Library.jsx';
import LibraryCollections from './screens/LibraryCollections.jsx';
import LibraryFilms from './screens/LibraryFilms.jsx';
import Person from './screens/Person.jsx';
import Player from './screens/Player.jsx';
import { useSession } from './state/useSession.js';

function NotFound() {
  return (
    <div className="state">
      <LogoMark size={48} />
      <h1 className="state__title">Nothing here</h1>
      <p className="state__text">That page isn&rsquo;t in the library.</p>
    </div>
  );
}

/**
 * Routes plus the session-expired card. The card is an overlay: the current route (and a
 * playing video) stay mounted underneath, so signing in returns exactly where you were.
 */
export default function App() {
  const session = useSession();
  return (
    <>
      <Routes>
        <Route path="/films/:id/play" element={<Player />} />
        <Route element={<Layout />}>
          <Route index element={<Home />} />
          <Route path="library" element={<Library />}>
            <Route index element={<LibraryFilms />} />
            <Route path="collections" element={<LibraryCollections />} />
          </Route>
          <Route path="films/:id" element={<FilmDetail />} />
          <Route path="people/:id" element={<Person />} />
          <Route path="collections/:id" element={<Collection />} />
          <Route path="admin" element={<Admin />} />
          <Route path="*" element={<NotFound />} />
        </Route>
      </Routes>
      {session.expired ? (
        <SignInCard
          error={session.error}
          submitting={session.submitting}
          onSignIn={session.signIn}
        />
      ) : null}
    </>
  );
}
