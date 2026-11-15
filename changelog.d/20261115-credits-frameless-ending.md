### Fixed — A film whose last seconds have no picture now gets its credits analysed

Some files have no decodable picture in their final few seconds, so the credits check failed on them every time and tried again after every restart. A seek in the last ten seconds that returns no picture now simply ends that pass, and the credits are found from the frames before it. Any other failure still skips the film.
