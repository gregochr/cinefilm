### Added — credits detection analysis

Pure analysis code that works out where a film's end credits start from greyscale frames: it measures the picture area, classifies each frame as black, text on dark or picture, confirms a crawl or cards of text, finds the start to the half second and checks it against the edition timeline. Nothing uses it yet: phase 1b reads films and stores the result, and phase 3 applies the markers.
