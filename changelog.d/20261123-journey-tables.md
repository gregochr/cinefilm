### Added — the tables for a train journey's data

Six new database tables (a migration, their entities and repositories) will hold the train feed and laptop logs the owner records on train journeys: devices, journeys, 30-second samples, modems and their samples, and the link from a journey to the playback records watched during it. Nothing reads or writes them yet; the import and the views come in later changes.
