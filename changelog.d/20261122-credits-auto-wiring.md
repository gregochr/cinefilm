### Added — credits AUTO markers: the switch, the deny file and the pass's reads

The credits apply pass now reads the deny file (`cinefilm.credits.auto.denylist`, mounted read-only at
`/credits-auto`) at its start, keeps the last good copy in force, and reads detection rows and unit-key inputs for
step 3. Admin Clear uses the same list and inputs. The switch `cinefilm.credits.auto.enabled` is off by default, so
nothing changes until it is turned on. The pass log gains the `AUTO` count and why `AUTO` is off.
