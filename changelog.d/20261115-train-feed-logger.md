### Added — A logger for the train's own Wi-Fi feed

`scripts/train-feed-logger.py` is a measuring script run by hand on a train. Every 30 seconds it asks the train's own
Wi-Fi system where the train is, how strong each of its mobile connections is and how many passengers are online,
times one tiny request to the internet and, when no film is playing, times a download of CineFilm's own page code.
Each round is a line in `~/train-feed-logger/logs`. It is not part of the app; `docs/engineering/train-feed-logger.md`
says what it is for and what nobody has verified, which includes a real train.
