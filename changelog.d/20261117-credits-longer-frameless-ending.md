### Fixed — A film with about twenty seconds of no picture at the end now gets its credits analysed

A second film ended in about 21 seconds with no decodable picture, longer than the ten seconds the credits check already forgave, so it still failed every time. A seek in the last thirty seconds that returns no picture now ends that pass, and the credits are found from the frames before it. Any other failure still skips the film.
