// Every word on the Admin "End credits" card and list, in one place. No JSX: the Apple TV and native clients reuse it.
// Source: docs/engineering/credits-3b-admin-plan.md, "The screen".

export const creditsCard = {
  title: 'End credits',
  tilesLabel: 'End credits counts',
  needs: 'Needs a look',
  plex: 'From Plex',
  owner: 'Yours',
  review: 'Review end credits…',
  loadError: 'Couldn’t load the end credits counts.',
  checkAgain: 'Check again',
};

export const creditsPage = {
  back: '← Library',
  title: 'End credits',
  tabsLabel: 'End credits lists',
  listLabel: 'End credits',
  tabs: { none: 'Needs a look', plex: 'From Plex', owner: 'Yours', all: 'All' },
  empty: 'No films here',
  loadingLabel: 'Loading end credits',
  loadError: 'Couldn’t load the end credits list.',
  checkAgain: 'Check again',
  previous: 'Previous',
  next: 'Next',
  pagerLabel: 'Pages',
  look: 'Look and set…',
};

export const creditsPanel = {
  stillsLabel: (title) => `Stills for ${title}`,
  earlier: '30 s earlier',
  atTime: 'At the time',
  frame: (clock) => `Frame at ${clock}`,
  noStill: 'No still for this time',
  tryAgain: 'Try again',
};

export const creditsRow = {
  fromPlex: 'From Plex',
  yours: 'Yours',
  setAutomatically: 'Set automatically',
  notSettled: 'Not settled yet',
  noMarker: (percent) => `No marker · the end screen shows at ${percent}%`,
  plexNone: 'Plex has no marker for this film',
  plexNoFingerprint: 'This file hasn’t been fully scanned, so Plex’s marker can’t be matched to it',
  plexStale: 'Plex’s marker is for an older copy of this file',
  plexUsable: (clock) => `Plex’s marker at ${clock} is ready but not applied yet`,
  plexOutside: 'Plex’s marker is past the end of this file',
  plexRefused: (clock) => `Plex’s marker at ${clock} is too early or too late to trust`,
  noTimeline: 'This film’s length isn’t known',
};
