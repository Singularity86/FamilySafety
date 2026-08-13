# Release notes — 1.12.7 (versionCode 25)

Fixes a defect that split a family's member list in two, found by running two devices
against each other for the first time on 2026-08-12. 1.12.6 (24) is the build in Play now;
everything below is new since it.

## Play Console "What's new"

```
Fixes a problem that could leave family members missing from each other's lists.

• If two people accepted a join request at the same moment, the family could split in two — some phones saw one list, some saw another, permanently. Devices now reconcile automatically.
• Removing someone now sticks, even if another phone was mid-change
• Removed a stream of harmless errors logged whenever someone joined

Everyone should update — older versions can't reconcile and will drift apart.
```

## What happened

A phone sat on a three-member roster while the rest of the family ran four. It was
*receiving* the fourth member's messages the whole time and discarding each one as coming
from someone it didn't recognise. Nothing was broken on the relay, and nothing was
misconfigured.

Any member can approve a join. Two members approved at nearly the same moment, so both
devices built a new state numbered version 5 from the same version 4 — same number,
different contents. The sync layer's rule for "a message at the version I already have" was
to acknowledge it and move on, which is correct when the two states match and silently
wrong when they don't. Both sides reported themselves in sync while holding different member
lists, and every later update failed its integrity check because it descended from the other
branch. There was no path back.

The trigger is ordinary — two parents approving a kid's join at the same time — and the
failure is invisible, because "someone is missing from my list" looks exactly like "they
haven't set it up yet."

## What changed

- **Concurrent edits now reconcile instead of forking.** When two devices hold different
  states at the same version, both independently pick the same one to build on (the one with
  the smaller state hash — already identical across devices, so no coordination is needed),
  and the other re-parents its changes onto it. The result is the union of both member
  lists, and it arrives as an ordinary next-version update the first device accepts through
  the existing rules. One round trip, nothing for anyone to decide.
- **Removals are now recorded, not just applied.** Merging two member lists by combining
  them would put back anyone who had just been removed, because "they removed this person"
  and "they haven't heard of this person yet" look the same from the outside. Removals now
  leave a permanent marker that travels with the group state, is covered by the signature so
  it can't be stripped in transit, and always wins over a re-add. Removal is permanent for
  that identity, which costs nothing: anyone rejoining generates a new recovery phrase and
  therefore a new identity anyway.
- **Retained-message cleanup no longer looks like a corrupt message.** Clearing a retained
  message publishes an empty one, and every device tried to parse that as a join request,
  logging a full stack trace each time a join completed. Harmless, but it buried real
  errors. Empty payloads are now ignored where messages are routed, so this can't recur on
  another topic.

## Compatibility

`PROTOCOL_VERSION` is now **3**. A 1.12.6 device parses the new group state but drops the
removal markers when it passes it on, so it would readmit removed members and disagree about
the group's state hash. It also can't reconcile, so it stays forkable. Those peers show as
"needs to update" on the Family screen — the mechanism added in 24, earning its keep on the
first release after it shipped.

Groups that have never removed anyone keep exactly the state hash they had before, so this
does not disturb an existing family beyond the update itself.

## Rollout

The recreation described in the 1.12.6 notes is still required to switch on the file,
file-name and presence protections for families created before 1.12.0 — that hasn't changed.
Do it **on 25, not 24**: recreating a family means several people joining in quick
succession, which is exactly the concurrency that triggered this bug.

Everyone updates → everyone leaves → **one person creates the family on 25** → re-invite.
Leaving destroys local group data and identity keys; **old recovery phrases become
worthless**. Tell people before they leave.

## Verification

329 unit tests, 0 failures (up from 307). The new coverage drives both sides of an exchange
rather than checking the merge in isolation, since a merge that is individually correct on
each device can still fail to agree: concurrent additions converging to the union, three-way
forks converging regardless of the order devices meet, idempotency, and — the one that
matters most — a removed member not returning when the other side still lists them.

Still only verified between two devices for the basics; the reconciliation path has not yet
been exercised on real hardware. Reproducing it is straightforward: two phones approve a
join at the same time and confirm both member lists end up complete.

## Build details

| | |
|---|---|
| Package | `jibaro.spacepirate.love` |
| versionName / versionCode | 1.12.7 / 25 |
| targetSdk / minSdk | 36 (Android 16) / 26 |
| Upload artifact | `app/build/outputs/bundle/release/app-release.aab` |
