### Fixed — an id can no longer change which API path the browser asks for

Every id the browser puts into a request path is now encoded as a single path segment (`pathSegment` in
`frontend/src/api`), and `.` and `..` are refused, so an id taken from a crafted link or from a response cannot add a
segment, climb to another endpoint or start a query. Real ids (numbers and UUIDs) are sent exactly as before. The
fixture server's placeholder address is now `https`. This is what SonarQube Cloud's two findings on the fetch client
and its one on the fixture server were about.
