### Docs — 3e: dry run failed on the small model's labels; a better language detector is tried

The first dry run confirmed 2 of 26 design files, so 3e now tries two stronger language labellers (Whisper base, and a dedicated SpeechBrain language model) on films already seen, with the unseen films still unspent; if neither passes, 3e ends.
