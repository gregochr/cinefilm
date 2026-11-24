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

export const journeyList = {
  title: 'Stored journeys',
  label: 'Stored journeys',
  loadingLabel: 'Loading journeys',
  loadError: 'Couldn’t load the journeys.',
  checkAgain: 'Check again',
  empty: 'No journeys yet. Import a log above.',
  noSamples: 'No samples',
  unitNotNamed: 'Unit not named',
  part: (n) => `part ${n}`,
  classes: { standard: 'Standard', first: 'First' },
  rounds: (n) => count(n, 'round', 'rounds'),
  viewings: (n) => (n === 0 ? 'no viewings' : count(n, 'viewing', 'viewings')),
  train: (headcode, date) => `Train ${headcode} on ${date}`,
  noTrain: 'No train yet',
};

export const journeyDelete = {
  open: 'Delete…',
  spoken: (title) => `Delete the journey logged ${title}`,
  spokenNoSamples: 'Delete the journey with no samples',
  group: 'Delete this journey?',
  sentence: (rounds) =>
    `Delete this journey and its ${count(rounds, 'round', 'rounds')}? The log on the laptop is not touched.`,
  sentenceNoSamples: 'Delete this journey? The log on the laptop is not touched.',
  confirm: 'Delete journey',
  deleting: 'Deleting…',
  keep: 'Keep',
  toast: 'Journey deleted',
  goneToast: 'That journey was already deleted',
  conflict: 'Another request was running. Try again.',
  failed: 'Couldn’t delete the journey. Try again.',
};

export const journeyTrainRemove = {
  open: 'Remove train…',
  spoken: (title) => `Remove train from the journey logged ${title}`,
  spokenNoSamples: 'Remove train from the journey with no samples',
  group: 'Remove this train?',
  sentence: (headcode) =>
    `Remove train ${headcode} from this journey? The journey and its rounds stay.`,
  confirm: 'Remove train',
  removing: 'Removing…',
  keep: 'Keep',
  toast: 'Train removed',
  conflict: 'Another request was running. Try again.',
  failed: 'Couldn’t remove the train. Try again.',
};

export const journeyTrain = {
  add: 'Add train file…',
  replace: 'Replace train file…',
  spoken: {
    add: (title) => `Add train file to the journey logged ${title}`,
    addNoSamples: 'Add train file to the journey with no samples',
    replace: (title) => `Replace train file of the journey logged ${title}`,
    replaceNoSamples: 'Replace train file of the journey with no samples',
  },
  group: 'Train file for this journey',
  text: 'Choose the file with this train’s run: its timetable id, running date, headcode and its times at each timing point. The whole file is sent.',
  label: 'Train file',
  sentHeading: 'What will be sent',
  ready: (headcode) => `Ready to send: train ${headcode}`,
  terms: {
    uid: 'Timetable id',
    date: 'Running date',
    headcode: 'Headcode',
    calls: 'Timing points',
    from: 'From',
    to: 'To',
  },
  sendAdd: 'Add train',
  sendReplace: 'Replace train',
  storing: 'Storing…',
  cancel: 'Cancel',
  toast: (headcode, calls, replaced) =>
    `Train ${headcode} ${replaced ? 'replaced' : 'added'}: ${count(calls, 'timing point', 'timing points')}`,
  unreadable: 'That file couldn’t be read.',
  problems: {
    tooBig: 'That file is too big to be a train file.',
    notJson: 'That file isn’t JSON.',
    notATrainFile:
      'That file isn’t a train file. It needs a uid, a date, a headcode and at least two calls, each with a location.',
  },
  didNotRun: 'That train did not run during this journey. Nothing was stored.',
  refused: 'The server refused this train file. Nothing was stored.',
  otherEnds: 'This train is already stored with other end points. Nothing was changed.',
  conflict: 'Another request was running. Try again.',
  failed: 'Couldn’t store the train. Try again.',
};

export const journeyImport = {
  title: 'Import a journey',
  text: 'Choose a log the train feed logger wrote. It is read here, in this browser, and only what is listed below is sent.',
  label: 'Logger file',
  sentHeading: 'What will be sent',
  leftOutHint: 'The values left out were out of range or of the wrong kind. They are not sent.',
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
