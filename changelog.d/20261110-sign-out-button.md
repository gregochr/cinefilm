### Added — Sign out button on Admin

Admin now ends with a Session section holding a Sign out button. It signs the browser out and shows the sign-in card
with "Signed out" copy ("You've signed out of CineFilm. Enter the password to sign back in."), distinct from the
"signed out while you were away" copy shown when the session expires. Later 401s from background polling do not
change that copy, and signing in again closes the card and reloads the page's data as before. The sign-in card now shows the CineFilm wordmark beside the logo, like the header. A failed sign-out shows
"Couldn't sign out. Try again." and re-enables the button.
