import { useEffect, useRef, useState } from 'react';
import { Button } from './Button.jsx';
import LogoMark from './LogoMark.jsx';

/** The §5 "signed out while you were away" card, shown over whatever the user was doing. */
export default function SignInCard({ reason = 'expired', error, submitting, onSignIn }) {
  const [password, setPassword] = useState('');
  const field = useRef(null);
  useEffect(() => {
    field.current?.focus();
  }, []);

  const submit = (event) => {
    event.preventDefault();
    onSignIn(password).then((ok) => {
      if (ok) setPassword('');
    });
  };

  return (
    <div className="signin" role="dialog" aria-modal="true" aria-labelledby="signin-title">
      <form className="signin__card" onSubmit={submit}>
        <div className="wordmark">
          <LogoMark size={30} />
          <span>CineFilm</span>
        </div>
        <h2 id="signin-title" className="signin__title">
          Signed out
        </h2>
        {reason === 'signed-out' ? (
          <p className="signin__text">
            You&rsquo;ve signed out of CineFilm. Enter the password to sign back in.
          </p>
        ) : (
          <p className="signin__text">
            You&rsquo;ve been signed out while you were away. Sign in again and you&rsquo;ll be back
            where you were.
          </p>
        )}
        <label className="field">
          <span className="field__label">Password</span>
          <input
            ref={field}
            type="password"
            className="signin__input"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        {error ? (
          <p className="signin__error" role="alert">
            {error}
          </p>
        ) : null}
        <Button variant="primary" type="submit" disabled={submitting || !password}>
          Sign in
        </Button>
      </form>
    </div>
  );
}
