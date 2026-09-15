# 0020 — Working agreements for dynamic overlays, carousel and zoom (M11–M13)

- **Status:** Accepted
- **Date:** 2026-09-15

## Context
While planning and building M11 (dynamic overlays), M12 (sponsor carousel) and M13 (camera zoom), the user made
decisions about **how** the work runs, not about code. Agents that resume the work in a new context need them, and
they are not visible in the code.

## Decision
1. **Scope:** M11–M13 are Android only. The iOS plugin is a stub; the ported features wait in the parity backlog of
   `docs/plans/ios.md`.
2. **Requirements first:** big features start with a question round until every requirement is agreed. Agreed decisions
   go into the milestone plan's decision table with a date, **and** into the spec, an ADR or a `.claude/rules` file
   that enforces them. Suggestions are welcome; assumptions are not.
3. **Tracking:** every milestone has a plan in `docs/plans/` with a "Resume here" box (status, next step, blockers,
   date), checkboxes per subtask, and a findings log. The box and boxes are updated as work lands, so work can resume
   after a context reset.
4. **No device spikes; batched device testing:** designs are chosen from the library sources (RootEncoder, libuvc).
   All device testing happens at the end of the milestone from the checklists in the plans. Results go into the
   spec's device-results section. Until then, README, specs and CHANGELOG say "not device-verified".
5. **Branch:** M11, M12 and M13 share `feature/dynamic-overlays`.
6. **Commits and merge:** the user commits manually. Agents don't commit, push, tag or merge unless the user asks.
   Merge to `main` only after the device checks and `release-check`, on the user's approval.
7. **Testable example:** every new capability is wired into the example app so it can be tried before **and** during
   a stream, with mock data: overlay features in the Overlay Studio (`example/lib/overlay_studio/`), camera features
   on the Go Live screen.

## Consequences
- The working tree can hold a lot of uncommitted work. Resume with `git status`, not `git log`.
- A feature is not "done" until its device checklist passes, even when code, tests and docs are complete.
- Spec DRAFT banners are removed only after the device checks.
