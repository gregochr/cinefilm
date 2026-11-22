### Added — single-frame reads for checking the automatic credits markers

The frame reader can now take one frame at a time, in greyscale or as a colour PNG at the file's own resolution, and tell a seek past the end of a file from a failure. Nothing uses it yet; the sheet tool that checks the automatic markers will.
