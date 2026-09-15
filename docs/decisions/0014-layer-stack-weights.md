# 0014 — Single layer stack with 0–100 weights for all overlays

- **Status:** Accepted (ordering device-verified 2026-09-15)
- **Date:** 2026-09-15

## Context
Dynamic overlays (M11) need arbitrary z-ordering. Today render order is implicit: sponsors are added first,
and the scoreband is added last (lazily). RootEncoder 2.7.2 `GlStreamInterface` supports `addFilter(index, filter)`,
but filter ops are queued to the GL thread. Existing layers must keep their order without app changes.

## Decision
- Every GL overlay layer (sponsor, scoreband, dynamic) is registered in one native `LayerStack`, sorted back → front by `(weight asc, classRank asc, seq asc)`. classRank: sponsor 0, scoreband 1, dynamic 2.
- Default weights: sponsors 10, scoreband 50, dynamic 50. That reproduces today's order, with later dynamic overlays on top at ties.
- Insert position is computed from the stack and applied with `addFilter(index, …)`. All ops are issued sequentially from the main thread, so queued indices stay consistent.
- Weight changes = remove + insert. Hidden overlays leave GL but keep their key.
- After pipeline transitions, `LayerStack.rebuild` re-adds everything in order. This replaces ad-hoc re-apply code.

## Consequences
- Legacy API gains only an optional `weight`.
- classRank makes scoreband placement deterministic even though its filter is created lazily.
- Ordering correctness depends on no other code calling `addFilter` directly. All filter mutations must go through `LayerStack`.
- Validated on device in spike S1; if queued indices prove unreliable, fallback is full `clearFilters` + re-add on reorder.
