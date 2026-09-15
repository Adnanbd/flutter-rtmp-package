---
name: sync-docs
description: Bring docs in line after any code change in flutter_rtmp_broadcaster — specs, plans/checkboxes, ADRs, README, CHANGELOG. Use at the end of every feature, fix, or refactor, or when the user says "update docs", "sync docs", "mark done", or "update the plan". Required by CLAUDE.md before a task is considered complete.
---

# Sync docs after a change

Get the diff: `git diff --stat main...HEAD` (or the working tree). Go through each row and touch only what applies.

| If the change… | Update |
|---|---|
| adds/changes a channel method, arg, event, error/warning code | `docs/specs/channel-contract.md` (+ README error table) |
| changes public Dart API or models | `docs/specs/dart-api.md`, README API Reference, `///` docs |
| changes overlay math, layering, lifecycle | `docs/specs/overlay-compositing.md` |
| changes GL rotation, dims, orientation flow | `docs/specs/orientation.md`, README Orientation Handling |
| changes reconnect/bitrate | `docs/specs/reconnect-and-bitrate.md`, README Auto-Reconnect |
| touches USB | `docs/specs/usb-sources.md` |
| touches DiagLogger or logging conventions | `docs/specs/diagnostics.md` |
| adds/moves/renames native classes or files | `docs/architecture/<platform>.md` class map, `overview.md` table |
| makes or reverses a design choice | new ADR `docs/decisions/NNNN-*.md` + index row (never edit an accepted ADR's decision) |
| completes or starts milestone work | tick boxes in `docs/plans/{roadmap,android,ios}.md`; refresh "Current State" date |
| changes setup steps (Gradle, Podfile, manifest, Info.plist, ProGuard) | README setup sections |
| changes known limitations or platform support | README tables |
| user-visible change | `CHANGELOG.md` under `## Unreleased` |
| a new non-obvious gotcha an agent would trip on | the relevant `.claude/rules/*.md` (keep each ≤ ~40 lines) |

## Rules
- Specs describe **current** behavior. Delete outdated statements; don't append "update:" notes.
- Plans hold progress only. Never put contract detail there.
- Use absolute dates (`2026-09-15`), never "today" or "last week".
- Keep `CLAUDE.md` short. Add a pointer there only if a new top-level doc or skill was created.

## Checks
```sh
# broken relative links in docs
for f in $(git ls-files 'docs/*.md' CLAUDE.md README.md '.claude/**/*.md'); do
  d=$(dirname "$f")
  grep -oE '\]\([^)#]+\.md' "$f" | sed 's/](//' | while read -r l; do
    [ -e "$d/$l" ] || echo "BROKEN $f -> $l"
  done
done
```
Then run the drift check from skill `add-channel-method` if channels changed.
