### Added — the reading of a train feed logger file for Admin's coming Journeys page

`frontend/src/state/journeyFile.js` turns the text of one logger file (versions 2 and 3) into the request of the journey import, in the owner's own browser: only the named columns are kept, values the server would refuse are left out and counted, and the laptop's addresses, the feed's addresses and the Wi-Fi network's name never leave the file. It also gives the summary the page will show before sending. Nothing shows it yet.
