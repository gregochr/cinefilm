/** The largest train file the server takes, in characters. */
export const MAX_TRAIN_FILE_CHARS = 200000;

const isText = (value) => typeof value === 'string';

/** Whether a train file has what the page needs to show it: text id, date and headcode, and two calls with places. */
function isTrainFile(file) {
  return (
    typeof file === 'object' &&
    file !== null &&
    !Array.isArray(file) &&
    isText(file.uid) &&
    isText(file.date) &&
    isText(file.headcode) &&
    Array.isArray(file.calls) &&
    file.calls.length >= 2 &&
    file.calls.every((call) => isText(call?.location))
  );
}

/**
 * Reads the text of a train's file, in the browser. Answers `{ ok: true, request, summary }`, where `request` is the
 * file exactly as parsed (the server judges everything else) and `summary` is `{ uid, date, headcode, calls, from, to }`
 * (`calls` the count, `from` and `to` the first and last call's place), or `{ ok: false, problem }` with `problem` one
 * of `tooBig`, `notJson` and `notATrainFile`. It never throws.
 */
export function readTrainFile(text) {
  if (text?.length > MAX_TRAIN_FILE_CHARS) return { ok: false, problem: 'tooBig' };
  let request;
  try {
    request = JSON.parse(text);
  } catch {
    return { ok: false, problem: 'notJson' };
  }
  if (!isTrainFile(request)) return { ok: false, problem: 'notATrainFile' };
  const { uid, date, headcode, calls } = request;
  return {
    ok: true,
    request,
    summary: {
      uid,
      date,
      headcode,
      calls: calls.length,
      from: calls[0].location,
      to: calls.at(-1).location,
    },
  };
}
