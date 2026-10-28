# Changelog

Notable changes to Cadence. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-10-28

First release. Everything below is new, so this entry records the decisions worth knowing rather
than a list of additions.

### Added

- **Cron parsing** (`cron`): five fields, ranges, lists, steps, three-letter names, and `@hourly`
  through `@yearly`. Each field is a bitmask in one `long` — the widest field is the minute at sixty
  values, which fits exactly, so matching is a shift and a mask.
- **Next-fire-time computation**, strictly after the instant given, skipping whole months when the
  month field does not match and giving up after eight years.
- **Job model** (`job`): specs, a declared state machine, sortable ids, and retry policies with
  geometric backoff and proportional jitter.
- **Fire-time queue** (`queue`): a binary min-heap with a job-id index, so removing and rescheduling
  are logarithmic rather than linear.
- **Scheduler** (`exec`): one thread owning the queue, a bounded worker pool running the jobs, and a
  `Clock` interface so the whole thing can be tested without sleeping.
- **Journal** (`store`): an append-only event log with a CRC32 per record, recovery from a truncated
  tail, and an atomic compacting rewrite. Includes a hand-rolled JSON reader and writer.
- **Metrics** (`metrics`): counters and a bucketed latency histogram.
- **Command line** (`cli`): `next`, `validate`, `replay`, `demo`, `version`, `help`.
- **CI**: tests on JDK 21 and 25 on Linux plus one JDK each on Windows and macOS, a javadoc gate, a
  check that every import is from the JDK, and an end-to-end run of the real jar.

### Decisions worth recording

- **No dependencies, at build time or run time.** A scheduler ends up embedded in someone else's
  service, and every dependency it carries becomes a version conflict in their build. The cost is a
  JSON codec, a test harness, a worker pool and a histogram written here rather than depended on —
  all of which have better equivalents on Maven Central.
- **No seconds field.** A scheduler firing on the second invites schedules it cannot honour, since
  the queue operation, the journal write and the handoff to a worker all cost more than the interval
  does. The field would be there to be misused.
- **The two cron day fields are unioned when both are restricted.** `0 0 1 * MON` fires on the 1st
  *and* on every Monday. This is not a bug kept for compatibility: intersecting them would make the
  pair match almost nothing. `cadence validate` says so out loud whenever it sees the case.
- **The journal is a log, not a table.** A table of current state must be rewritten in place, so a
  crash can leave a job in no state at all. A log can only be damaged at its tail.
- **The worker pool's queue is bounded and refuses.** `newFixedThreadPool` uses an unbounded queue,
  so a backlog becomes an `OutOfMemoryError` two hours later — a far worse failure than a counted
  rejection at the moment the backlog starts.
- **One scheduling thread.** "Fire the job, mark it running, write the record" has to be atomic, and
  there is no arrangement of locks that makes double-firing obviously impossible. One thread does.

### Fixed before release

Three bugs found while building, each recorded because of how it was found rather than what it was:

- **A retry was slept through entirely.** `submit` woke the clock when it changed the queue;
  `complete` did not. A worker finishing a job would queue its retry while the scheduling thread
  slept on a wait computed before that retry existed, so a retry due in 200ms waited out the
  30-second idle ceiling. Every unit test passed — they all drive `fireDueJobs` directly and never
  exercise the loop's sleeping. **The CLI demo found it**: a handler written to fail once and then
  succeed simply never succeeded.
- **A fire the pool refused was left stranded.** The rejection branch assigned `null` to a loop
  variable — a no-op — so the job stayed in `RUNNING` with its attempt counted and nothing to move
  it out. Found by writing the test that was supposed to confirm it already worked.
- **A histogram's bucket bounds were half their correct values.** `1L << bucket - 1` parses as
  `1L << (bucket - 1)` in Java, because `-` binds tighter than `<<`. Found by writing out a table of
  eleven samples and their expected bounds, which is the only way a factor-of-two error in a
  logarithmic bucket is visible.

Two further changes came out of making guard rails testable rather than from failures:
`Scheduler.unregister` exists because the "handler is no longer registered" branch could not be
reached through the public API at all, and `WorkerPool.inFlightCount` exists because queue depth and
busy count both read zero in the window between a worker taking a task and marking itself busy.

### Known limitations

No distributed scheduling, no enforcement of `JobSpec.timeout`, no cancelling a running job, replay
linear in journal length, settled jobs never trimmed from memory, and one time zone for the whole
scheduler. See the README and `docs/operations.md`.

[0.1.0]: https://github.com/Mohammed-Abdul-Raqueeb/cadence/releases/tag/v0.1.0
