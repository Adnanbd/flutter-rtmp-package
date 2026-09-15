---
name: sync-docs
description: Bring docs in line after any code change in flutter_rtmp_broadcaster — specs, plans/checkboxes, ADRs, README, CHANGELOG. Use at the end of every feature, fix, or refactor, or when the user says "update docs", "sync docs", "mark done", or "update the plan". Required by CLAUDE.md before a task is considered complete.
---

# Sync docs after a change

Get the diff: `git diff --stat main...HEAD` plus `git status` (the user commits manually, so work is often uncommitted).
Go through each row and touch only what applies.

| If the change… | Update |
|---|---|
| adds/changes a channel method, arg, event, error/warning code | `docs/specs/channel-contract.md` + README API reference, `RtmpStatusType` table, error/warning tables |
| changes public Dart API or models | `docs/specs/dart-api.md`, README API reference + the matching guide section, `///` docs |
| changes sponsor/scoreband math, layering, GL filter lifecycle | `docs/specs/overlay-compositing.md` |
| changes dynamic overlays (content, placement, lifecycle, timer, ticker, animation, carousel, wire, limits) | `docs/specs/dynamic-overlays.md` (§8 wire, §9 codes, §10 limits) + README Dynamic Overlays |
| changes camera zoom | `docs/specs/camera-zoom.md` + README Camera Zoom |
| changes GL rotation, dims, orientation flow | `docs/specs/orientation.md`, README Orientation |
| changes reconnect/bitrate | `docs/specs/reconnect-and-bitrate.md`, README Reconnect & Adaptive Bitrate |
| touches USB | `docs/specs/usb-sources.md` |
| touches DiagLogger or logging conventions | `docs/specs/diagnostics.md` |
| adds/moves/renames native classes or files | `docs/architecture/<platform>.md` class map, `overview.md` diagram + table |
| makes or reverses a design choice | new ADR `docs/decisions/NNNN-*.md` + index row (never edit an accepted ADR's decision, only its status line) |
| the user states a requirement, default, limit, or way of working | record it where it is **enforced**: the spec (contract), an ADR (design or process, e.g. ADR 0020), or `.claude/rules/*.md` (how to code). A plan's decision table alone is not enough |
| completes or starts milestone work | tick boxes and update the "Resume here" box of the milestone plan (`docs/plans/roadmap.md`, `android.md`, `ios.md`, `dynamic-overlays.md`, `carousel-and-zoom.md`); refresh the roadmap "Current State" date |
| adds an example app hook | README Example App; `.claude/rules/example-app.md` if it sets a new pattern |
| changes setup steps (Gradle, Podfile, manifest, Info.plist, ProGuard) | README Installation & Setup |
| changes limits, known limitations, or platform support | README feature matrix, Limits, Known Limitations |
| user-visible change | `CHANGELOG.md` under `## Unreleased` (mark breaking changes, e.g. new `RtmpStatusType` values or `OverlayContent` subclasses) |
| a new non-obvious gotcha an agent would trip on | the relevant `.claude/rules/*.md` (keep each ≤ ~40 lines) |

## Rules
- Specs describe **current** behavior. Delete outdated statements; don't append "update:" notes.
- Plans hold progress, decision tables and findings logs only. Never put contract detail there.
- Use absolute dates (`2026-09-15`), never "today" or "last week".
- Features not yet checked on a device stay marked "not device-verified" in README, specs and CHANGELOG.
- Keep `CLAUDE.md` short. Add to it only for a new top-level doc, skill, invariant or working agreement.
- Don't commit. The user commits manually (ADR 0020).

## Checks
```sh
# broken relative links in docs
for f in $(git ls-files 'docs/*.md' CLAUDE.md README.md '.claude/**/*.md'); do
  d=$(dirname "$f")
  grep -oE '\]\([^)#]+\.md' "$f" | sed 's/](//' | while read -r l; do
    [ -e "$d/$l" ] || echo "BROKEN $f -> $l"
  done
done

flutter analyze lib test && flutter test
(cd example && flutter test)                                                    # example analyze has known old warnings; check for new ones
(cd example/android && ./gradlew :flutter_rtmp_broadcaster:testDebugUnitTest)   # when android/ changed
```
Then run the drift check from skill `add-channel-method` if channels changed. New files (`git status` `??`) need
`git ls-files`-independent checks: run the link loop over them too.
