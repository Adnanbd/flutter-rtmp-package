# 0010 — Android: lazy scoreband filter, setImage before addFilter

- **Status:** Accepted
- **Date:** 2026-04-25

## Context
Adding an `ImageObjectFilterRender` before `setImage` can leave an unbound GL texture that later
`setImage` calls don't fix. The original design preallocated a 1×1 transparent scoreband placeholder.

## Decision
Order is always `setImage → setScale → setPosition → addFilter`. The scoreband filter is created on
the first `updateScoreband`. No placeholder bitmaps.

## Consequences
- Scoreband is the last filter added, so it renders on top as long as sponsors are added first.
