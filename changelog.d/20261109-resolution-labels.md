### Fixed — resolution labels read the width, so letterboxed films say 1080p

The film page's file line and the player's Original quality row labelled a file by its pixel height, so a
letterboxed 1080p rip (1920x796) read "796p". Resolutions are now classified by width (3840+ is 2160p (4K), 1920+ is
1080p, 1280+ is 720p, otherwise 480p) through one helper, and the film API file summary now carries `width`. The
transcoding ladder and its quality ids are unchanged.
