### Added — The train feed logger records the laptop's kind and state, and the class travelled

The log is now version 3. The `start` line gains `device` (model, chip, cores, threads and memory, read once) and
`travel_class` (the logger asks "Standard or first class?" after the question about the film, or `--class` answers it).
Every round gains `laptop`: power source, battery, low power mode, CPU speed limit (Intel Macs only), load, swap and
the signal, noise, rate and channel of the laptop's own Wi-Fi link. Each tool is read by absolute path with no shell,
a size and time limit, and only the parsed figures are logged. `--no-laptop` leaves it all out, and `--no-wifi` now
leaves out both Wi-Fi reads. `docs/engineering/train-feed-logger.md` and `SECURITY.md` say what is read.
