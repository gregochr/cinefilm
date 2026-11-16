// The one function every line the rig prints, and every text it takes from the page, passes through. What the page
// shows (the pill, a toast, an error) and what Chrome says is not trusted to be a single line of plain text: a line
// break would let it forge a line of the log, and a control or escape character would drive the terminal.

/** Run names (`--name`, and the name `analyse.mjs` takes) are folder names: 1 to 60 of these, and not `.` or `..`. */
const RUN_NAME = /^[A-Za-z0-9._-]{1,60}$/;

/**
 * The text as one line of printable ASCII, cut at `limit` characters: line breaks become spaces, every other
 * character outside the printable ASCII range becomes `?`. (A character outside the basic plane is two of them.)
 */
export function printable(value, limit) {
  return String(value)
    .replaceAll(/\r|\n/g, ' ')
    .replaceAll(/[^\x20-\x7e]/g, '?')
    .slice(0, limit);
}

/** Whether `name` may be used as a run's folder name. */
export function isRunName(name) {
  return typeof name === 'string' && RUN_NAME.test(name) && name !== '.' && name !== '..';
}
