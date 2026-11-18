/** Removes every `/` at the end of `path` ("/library//" becomes "/library", "/" becomes ""), in one pass, no regex. */
export function trimTrailingSlashes(path) {
  let end = path.length;
  while (end > 0 && path.charCodeAt(end - 1) === 47) end -= 1;
  return end === path.length ? path : path.slice(0, end);
}
