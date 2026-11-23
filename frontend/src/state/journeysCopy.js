/** The words of Admin's Train journeys page and of the card that opens it. No JSX: the components hold no sentence. */

const count = (n, one, many) => `${n.toLocaleString('en-GB')} ${n === 1 ? one : many}`;

export const journeysCard = {
  title: 'Train journeys',
  text: 'What the train’s Wi-Fi reported on a journey, kept beside the films played on it.',
  open: 'Open journeys…',
};

export const journeysPage = {
  back: '← Playback',
  title: 'Train journeys',
};

export const journeyImport = {
  title: 'Import a journey',
  text: 'Choose a log the train feed logger wrote. It is read here, in this browser, and only what is listed below is sent.',
  label: 'Logger file',
  sentHeading: 'What will be sent',
  leftOutHint:
    'Left out: a value the log held that was out of range or of the wrong kind. It is not sent.',
  button: 'Import',
  importing: 'Importing…',
  ready: (rounds) => `Ready to import: ${count(rounds, 'round', 'rounds')}`,
  toast: (journeys, rounds, linked) =>
    `Imported ${count(journeys, 'journey', 'journeys')}: ${count(rounds, 'round', 'rounds')}, ${
      linked === 0 ? 'no viewings linked' : `${count(linked, 'viewing', 'viewings')} linked`
    }`,
  fileNotSent: 'Not sent',
  terms: {
    fileName: 'File name',
    rounds: 'Rounds',
    logged: 'Logged',
    units: 'Train units',
    modems: 'Modems',
    version: 'Logger version',
    laptop: 'Laptop',
    travelClass: 'Class',
    leftOut: 'Left out',
    unreadable: 'Lines not read',
  },
  noUnits: 'None named',
  laptopKnown: 'Its model, chip and memory',
  laptopUnknown: 'Not in this log',
  classes: { standard: 'Standard', first: 'First' },
  classUnknown: 'Not answered',
  nothingLeftOut: 'Nothing',
  unreadable: 'That file couldn’t be read.',
  problems: {
    empty: 'That file has no logger lines in it.',
    noStart: 'That file has no start line, so it isn’t a train feed log.',
    unknownVersion: 'That log is from a logger version this page doesn’t know.',
    noRounds: 'That log has no rounds in it.',
    roundsNotRising: 'That log’s rounds are out of order.',
    tooManyRounds: 'That log has more than 2,000 rounds. One import takes 2,000 at most.',
    tooBig: 'That log is too big to send.',
  },
  refused: 'The server refused this log. Nothing was stored.',
  alreadyImported: 'This log is already imported.',
  otherImportRunning: 'Another import was running. Try again.',
  failed: 'Couldn’t import the log. Try again.',
};
