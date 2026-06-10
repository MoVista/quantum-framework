# Contributing to quantum-framework (Movista fork)

This document describes how Movista works with its fork of the quantum
framework and how to contribute changes back to the upstream
`end2endlogic-com` repository.

## Repository setup

This fork has two git remotes:

| Remote     | Points to                                   | Role                                              |
|------------|---------------------------------------------|---------------------------------------------------|
| `origin`   | `MoVista/quantum-framework`                 | Movista's fork. Where we push our branches.       |
| `upstream` | `end2endlogic-com/quantum-framework`        | The canonical repo. Where we contribute changes.  |

Confirm with:

```bash
git remote -v
```

If `upstream` is missing:

```bash
git remote add upstream https://github.com/end2endlogic-com/quantum-framework.git
```

## The mental model

There are two parallel lines of history that must **never cross via a merge in
the wrong direction**:

- **The clean `com.end2endlogic` line — `1.3.1-SNAPSHOT`.**
  This mirrors upstream. It is what we open pull requests against. Keep it
  pristine: no `com.movista` groupId, no Movista-only code.

- **The Movista product line — `movista-dev`.**
  This carries Movista-specific changes, including the pom rewrite from
  `com.end2endlogic` to `com.movista`. All Movista work lives here.

### Why you cannot contribute upstream from `movista-dev`

The `movista-dev` branch rewrites the Maven `groupId` from `com.end2endlogic`
to `com.movista` in every `pom.xml`. A pull request opened from `movista-dev`
would therefore try to rename the entire project's groupId upstream and would
be unmergeable.

**Contributions are always authored on a branch cut from the clean
`1.3.1-SNAPSHOT` line — never from `movista-dev`.**

## Procedure A — Contribute a change back to end2endlogic

```bash
# 0. Sync the clean snapshot line first (the fork's copy can lag upstream)
git fetch upstream
git checkout 1.3.1-SNAPSHOT            # local tracking branch off the clean line
git merge --ff-only upstream/1.3.1-SNAPSHOT
git push origin 1.3.1-SNAPSHOT         # keep the fork's copy current too

# 1. Branch off the CLEAN snapshot — never off movista-dev
git checkout -b feature/MOV-XXXX-short-desc 1.3.1-SNAPSHOT

# 2. Make the change. It must compile and test as com.end2endlogic.
#    Do NOT touch the groupId. Do NOT bring in Movista-only files.

# 3. Push to our fork and open the PR against upstream
git push origin feature/MOV-XXXX-short-desc
gh pr create --repo end2endlogic-com/quantum-framework \
  --base 1.3.1-SNAPSHOT --head MoVista:feature/MOV-XXXX-short-desc
```

The pull request flows `MoVista:feature/...` → `end2endlogic-com:1.3.1-SNAPSHOT`.
Because the branch carries `com.end2endlogic`, it merges cleanly.

## Procedure B — Pull the approved change back into Movista

Once the pull request is approved and merged upstream:

```bash
# 1. Refresh the clean line again (it now contains the merged PR)
git fetch upstream
git checkout 1.3.1-SNAPSHOT
git merge --ff-only upstream/1.3.1-SNAPSHOT
git push origin 1.3.1-SNAPSHOT

# 2. Merge the clean line INTO movista-dev (this direction only)
git checkout movista-dev
git merge 1.3.1-SNAPSHOT
#    -> Expect conflicts in pom.xml files: com.movista (ours) vs
#       com.end2endlogic (theirs). Resolve by KEEPING com.movista every time.
#       The actual code change comes in cleanly.
git push origin movista-dev
```

## Guardrails and why each exists

| Rule                                                          | Reason                                                                          |
|---------------------------------------------------------------|--------------------------------------------------------------------------------|
| Branch off `1.3.1-SNAPSHOT`, not `movista-dev`                | The branch must be `com.end2endlogic` to be mergeable upstream.                |
| Sync `1.3.1-SNAPSHOT` from `upstream` before branching        | The fork's copy can lag upstream; otherwise you branch from stale code.        |
| Merge direction is `1.3.1-SNAPSHOT → movista-dev`, never reverse | The reverse pushes `com.movista` poms into the clean line and poisons upstream PRs. |
| Keep `com.movista` when resolving pom conflicts               | `movista-dev` must stay buildable as the Movista product.                      |

## Notes

- The `com.movista` groupId rename is the root cause of the recurring pom merge
  conflicts in Procedure B. If the rename were expressed as a build-time
  profile/property instead of a committed change to every pom, both lines would
  share identical poms and the conflicts would disappear. Worth revisiting if
  the conflict tax becomes a burden.
- This procedure targets `1.3.1-SNAPSHOT`. Upstream also maintains
  `1.4.0-SNAPSHOT` and `main`. If upstream wants new work on a newer line,
  change the base branch in Procedure A accordingly.
