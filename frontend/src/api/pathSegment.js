// One value as one segment of a request path. No JSX, no DOM.

/**
 * A value as exactly one segment of a request path. `/`, `?`, `#`, `%` and the rest are percent-encoded, so a value
 * can never add a segment, start a query or end the path early. `.` and `..` are refused: no encoding protects
 * them, because the browser resolves them, encoded or not, before the request leaves.
 */
export function pathSegment(value) {
  const segment = encodeURIComponent(value);
  if (segment === '.' || segment === '..') throw new RangeError(`Not a path segment: ${segment}`);
  return segment;
}
