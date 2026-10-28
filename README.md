# Cadence

A job scheduler for the JVM with **no dependencies at all** — not at build time, not at run time.
Cron parsing, a fire-time priority queue, a bounded worker pool, retries with jittered backoff, and
an append-only journal so a restart does not lose state. Written against the JDK and nothing else.

```bash
./build.sh jar
java -jar build/cadence.jar next '*/15 9-17 * * MON-FRI' --count 4
```

```
*/15 9-17 * * MON-FRI  from 2026-10-28T08:50
  2026-10-28T09:00     (+10m)
  2026-10-28T09:15     (+15m)
  2026-10-28T09:30     (+15m)
  2026-10-28T09:45     (+15m)
```

## Why no dependencies

A scheduler ends up embedded in somebody else's service, and every dependency it carries becomes a
version conflict in their build. The cost is paid here instead.

It is not free, and the README should say so: this repository contains a JSON reader and writer, a
test harness, a worker pool and a latency histogram that all have better-maintained equivalents on
Maven Central. The JSON is the clearest case — about four hundred lines doing badly what Jackson
does well. It is only defensible because the journal writes a handful of known shapes, so the subset
needed is small and fully under test. There is no JUnit either, so `src/test/java/.../harness` is a
test framework nobody asked for.

If you want a scheduler with no dependencies, that is the trade. If you do not care, Quartz is more
featureful and better tested.

## Using it

```java
WorkerPool pool = new WorkerPool(4, 64, "cadence-worker");
Journal journal = Journal.open(Path.of("./cadence.journal"));

Scheduler scheduler = Scheduler.builder()
        .workers(pool)
        .journal(journal)
        .build();

// Handlers are registered by name, because a lambda cannot be written to a
// journal. The name is what survives a restart.
scheduler.register("nightly-report", context -> {
    reportService.build(context.payload());
});

scheduler.submit(JobSpec.forHandler("nightly-report")
        .name("nightly report")
        .schedule("0 2 * * *")
        .payload("{\"region\":\"eu\"}")
        .retryPolicy(RetryPolicy.standard())
        .build());

scheduler.start();
```

`Scheduler` is `AutoCloseable`: closing it stops the loop, drains the pool, and closes the journal.

## Commands

```bash
cadence next <cron> [--count n] [--from when]   when a schedule fires next
cadence validate <cron>                         check a schedule and describe it
cadence replay <journal>                        print a journal's history
cadence demo [--seconds n] [--journal path]     run a short demonstration
cadence version
cadence help
```

`validate` prints each parsed field and warns when both day fields are restricted, because that
case does not mean what most people expect:

```
$ cadence validate '0 0 1 * MON'
0 0 1 * MON  is valid
  minute        [0]
  hour          [0]
  day-of-month  [1]
  month         *
  day-of-week   [1]

  Note: day-of-month and day-of-week are both restricted, so this
  fires on days matching EITHER of them, not only days matching both.
```

## Cron

Five fields — minute, hour, day-of-month, month, day-of-week — plus `@hourly` through `@yearly`.
Ranges, lists, steps and three-letter names all work. Sunday is both `0` and `7`.

There are no seconds. A scheduler that fires on the second invites schedules it cannot honour: the
queue operation, the journal write and the handoff to a worker all cost more than the interval does.

The one rule worth knowing is that **the two day fields are unioned when both are restricted**, so
`0 0 1 * MON` fires on the 1st *and* on every Monday. [docs/cron-syntax.md](docs/cron-syntax.md)
has the rest.

## Retries

Geometric backoff with proportional jitter, four attempts by default.

The growth is the obvious part: a job that failed because a database was unreachable will fail again
a second later. The jitter is the part usually left out, and it is why the default is not zero —
when a shared dependency fails, every job depending on it fails at nearly the same moment, so a
purely exponential backoff retries them all at nearly the same moment too, and they stay
synchronised through every doubling. Spreading each delay over a window breaks that on the first
retry.

```java
RetryPolicy.builder()
        .maxAttempts(5)             // counts the first try, so this is four retries
        .baseDelay(Duration.ofSeconds(1))
        .maxDelay(Duration.ofMinutes(5))
        .multiplier(2.0)
        .jitter(0.2)                // each delay lands uniformly in ±20%
        .build();
```

## Durability

The journal is an append-only log of events, one per line, each carrying a CRC32 of its JSON:

```
cadence-journal 1
a3f9c012 {"kind":"submitted","job":"00001M61FS6AA0QV","at":1793000000000}
7b2e4d81 {"kind":"started","job":"00001M61FS6AA0QV","at":1793000060000}
```

A log rather than a table of current state, because a table has to be rewritten in place on every
change and a crash can catch it half-written. A log can only be damaged at its tail, which is
recoverable.

The checksum is not redundant with the JSON parser: a flipped digit inside a number parses perfectly
and yields a wrong timestamp. The parser guards the structure; the CRC guards the values.

Cadence does **not** give exactly-once execution. A crash between a job running and its record
reaching disk means the job runs again after a restart, so handlers should tolerate that.

## Building

Requires JDK 21 or newer. No Maven, no Gradle — a build tool here would exist only to download the
build tool.

```bash
./build.sh           # compile and test
./build.sh test cron # run only cases matching "cron"
./build.sh jar        # package build/cadence.jar
./build.sh clean
```

The build runs `javac -Xlint:all -Werror`, and CI adds `javadoc -Xdoclint:all -Xwerror`, a check
that every import is `java.*`, `javax.*` or this project's own, and an end-to-end run of the real
jar.

## What is in here

| | |
| --- | --- |
| Main source | 23 files, ~5,500 lines |
| Tests | 17 files, 350 cases |
| Dependencies | 0 |

```
src/main/java/dev/raqueeb/cadence/
  cron/     CronExpression, CronField, FieldType — parsing and next-fire-time
  job/      JobSpec, Job, JobState, JobId, RetryPolicy, JobHandler
  queue/    ScheduleQueue — a binary heap with an id index
  exec/     Scheduler, WorkerPool, Clock
  store/    Journal, JournalEntry, Json
  metrics/  Metrics, Histogram
  cli/      Main, Args
```

## Limitations

Worth reading before adopting it:

- **No distributed scheduling.** Two instances against one job set will both fire everything. No
  leader election, no shared lock.
- **Handler timeouts are not enforced.** `JobSpec.timeout` is accepted, validated and then ignored.
  A long-running handler holds its worker until it finishes.
- **A running job cannot be cancelled.** `cancel` stops a job from starting.
- **Replay is linear in the journal's length.** `Journal.rewrite` compacts, but nothing calls it on
  a schedule, so a long-lived scheduler's startup time grows with its history.
- **Settled jobs stay in memory.** They remain inspectable after leaving the queue, so a scheduler
  submitting many one-shot jobs grows a table that is never trimmed.
- **One time zone for the whole scheduler.** Per-job zones need daylight-saving rules applied per
  schedule, and a half-done version of that is worse than not having it.

[docs/operations.md](docs/operations.md) has the rest, including what to alert on.

## Documentation

- [docs/architecture.md](docs/architecture.md) — the decisions, and what each one cost
- [docs/cron-syntax.md](docs/cron-syntax.md) — the full syntax, with the surprising parts first
- [docs/operations.md](docs/operations.md) — sizing, metrics, journal recovery, shutdown

## Licence

MIT. See [LICENSE](LICENSE).
