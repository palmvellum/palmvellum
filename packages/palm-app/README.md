# `packages/palm-app`

Palm OS C application targeting m68k DragonBall, Palm OS 3.1 baseline.

Cross-target for all 19 supported devices via runtime `FtrGet`
feature detection.

## Build

```bash
docker run --rm -v "$PWD":/work -w /work \
  jichu4n/prc-tools-remix make
```

Output: `build/OracleHello.prc`

## Status

🚧 Scaffold pending. See task list and ROADMAP.
