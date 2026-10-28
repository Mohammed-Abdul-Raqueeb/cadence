# Architecture

What Cadence is shaped like, and what each choice cost. The code says what it does; this says what
the alternatives were.

## The shape of the thing

```
  JobSpec ──submit──▶ Scheduler ───────────────────────────────┐
  (handler name,          │                                    │
   cron, payload,         │ one thread owns this               │
   retry policy)          ▼                                    │
                   ┌──────────────────────┐                    │
                   │ ScheduleQueue        │                    │
                   │  binary min-heap     │                    │
                   │  + id → position map │                    │
                   └──────────┬───────────┘                    │
                              │ pollDue(now)                   │
                              ▼                                ▼
                     ┌─────────────────┐            ┌────────────────────┐
                     │ WorkerPool      │            │ Journal            │
                     │  bounded queue  │            │  append-only,      │
                     │  N threads      │            │  CRC32 per record  │
                     └────────┬────────┘            └────────────────────┘
                              │ JobHandler.run
                              ▼
                        complete() ──┐
                              ▲      │ requeue or retire
                              └──────┘
```

## One scheduling thread

Everything else follows from this. A single thread polls the queue, decides what fires, and marks
each job running. Workers only execute.

What it buys:

- **"Fire, mark running, journal" is atomic without trying.** Those three steps have to happen
  together or a crash between them leaves a job that ran but was never recorded, or recorded but
  never ran. One thread under one lock makes that free.
- **The queue needs no concurrent structure.** A plain `ArrayList` heap and a `HashMap` index are
  correct here, which is why `ScheduleQueue` can be a simple thing.
- **No double firing.** The hard bug in a multi-threaded scheduler is two threads both deciding the
  same job is due. There is no arrangement of locks that makes that obviously impossible; there is
  one thread, which does.

What it costs:

- **A slow decision delays every other job.** So handlers are collected under the lock and called
  outside it, and nothing that touches a network goes anywhere near the scheduling thread.
- **It is a single point of failure.** If the loop thread dies, nothing fires. It is wrapped in a
  `try`/`finally` that clears the running flag, so the death is at least visible.

## Sleeping, not polling

The loop waits until the head of the queue is due. The alternative is a fixed tick — wake every
second, check what is due — which is simpler and wrong in two directions at once: an idle scheduler
burns a wakeup a second for nothing, and a job scheduled between ticks fires late by up to the tick
interval. There is no interval that fixes both, and picking one means picking which of the two
problems to have.

Sleeping until the next fire has neither. It costs one thing: a submission arriving mid-sleep has to
cut the sleep short, so `Clock.wake()` exists and `submit` calls it.

**This is where the project's worst bug lived.** `submit` woke the clock; `complete` did not. A
worker finishing a job would queue its retry — or requeue a recurring job — while the scheduling
thread slept on a wait computed before any of that existed. A retry due in 200ms waited out the
30-second idle ceiling instead. Every unit test passed, because they all drive `fireDueJobs()`
directly and never exercise the loop's sleeping. The CLI demo found it: a handler written to fail
once and then succeed simply never succeeded.

The sleep is capped at 30 seconds for the same reason a belt has a second notch. If a wake is ever
missed again, the cost is latency rather than a job that never runs.

## The queue is a heap with an index

`ScheduleQueue` is a binary min-heap over an `ArrayList`, plus a `HashMap` from job id to heap
position.

`java.util.PriorityQueue` was the obvious choice and does not fit. Its `remove(Object)` is linear,
and it has no way to say "this element's key changed, re-heapify" — the only route is remove then
add, which is two linear scans. Rescheduling happens once per fire, so that cost lands on the hot
path: ten thousand queued jobs would mean scanning ten thousand entries to cancel one.

Ties break by job id, which is not a detail. Every `@daily` job fires at midnight, so simultaneous
jobs are the common case rather than the edge. Without a tiebreak their order falls out of heap
mechanics and differs on each restart. Ids sort by creation time, so the tiebreak is
first-submitted-first.

## Handlers are named, not held

A `JobSpec` stores the string `"nightly-report"`, and the scheduler holds a map from that name to
the code. The indirection is what lets a job survive a restart: a lambda cannot be written to a
journal.

It has a second benefit that was not the reason but is worth more than expected — the journal says
what each job was going to do, in a form an operator can read without the code in front of them.

The cost is that a name can be wrong. That is caught at `submit` rather than at fire time, because a
typo is a configuration error and the moment to report one is while the person who made it is still
watching, not at 3am when the job first fires.

## The journal is a log, not a table

Events, appended, never rewritten in place.

A table of current state has to be written over on every change, so a crash can catch it half-way
and leave a job in no state at all. An append-only log can only be damaged at its tail, and that is
recoverable: every record before the damage is intact, and replaying them rebuilds exactly the state
the scheduler had before the interrupted write.

Each line carries a CRC32 of its JSON. The checksum is not redundant with the parser: a flipped
digit inside a number parses perfectly and yields a wrong timestamp. The parser guards the
structure, the CRC guards the values.

Two kinds of damage, reported differently because they call for different responses:

| Damage | Signature | Response |
| --- | --- | --- |
| Incomplete final line | Process killed between write and flush | Drop it. Ordinary, expected |
| Checksum mismatch mid-file | Bytes changed after being written | Stop replay. Storage is damaged |

## Why there are no dependencies

A scheduler is the kind of thing that ends up embedded in somebody else's service, and every
dependency it carries becomes a version conflict in their build. The cost is paid here rather than
there.

It is not free. This repository contains a JSON reader and writer, a test harness, a worker pool and
a histogram that all have better-maintained equivalents on Maven Central. The JSON is the clearest
case: about four hundred lines to do badly what Jackson does well. It is only defensible because the
journal writes a handful of known shapes, so the subset needed is small and fully under test.

Where the rule genuinely hurt: there is no JUnit, so `src/test/java/.../harness` is a test framework
nobody asked for, and it has no parameterised tests, no fixtures and no IDE integration.

## What is deliberately missing

- **Seconds in the cron expression.** A scheduler that fires on the second invites schedules it
  cannot honour — the queue, the journal write and the handoff to a worker all cost more than the
  interval does. The field would exist to be misused.
- **Distributed scheduling.** No leader election, no shared lock, no way to run two instances
  against one job set without both firing everything. Out of scope by a wide margin.
- **Job dependencies.** No "run B after A". That is a workflow engine, which is a different thing
  wearing similar clothes.
- **Cancelling a running job.** `cancel` stops a job starting; it does not reach into a worker.
  Interrupting is offered at shutdown only, and a handler that ignores interruption outlives it.
- **A snapshot alongside the journal.** Replay is linear in the journal's length, so a long-lived
  scheduler's startup grows without bound. `Journal.rewrite` compacts, but nothing calls it on a
  schedule.
- **Time zones per job.** One zone for the whole scheduler. Per-job zones need daylight-saving rules
  applied per schedule, and the half-done version of that is worse than not having it.
