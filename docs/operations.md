# Operations

Running Cadence, and what to look at when it misbehaves.

## Embedding it

```java
WorkerPool pool = new WorkerPool(4, 64, "cadence-worker");
Journal journal = Journal.open(Path.of("./cadence.journal"));

Scheduler scheduler = Scheduler.builder()
        .workers(pool)
        .journal(journal)
        .build();

scheduler.register("nightly-report", context -> {
    reportService.build(context.payload());
});

scheduler.submit(JobSpec.forHandler("nightly-report")
        .name("nightly report")
        .schedule("0 2 * * *")
        .payload("{\"region\":\"eu\"}")
        .retryPolicy(RetryPolicy.standard())
        .timeout(Duration.ofMinutes(10))
        .build());

Thread loop = scheduler.start();   // or scheduler.run() on this thread
```

`Scheduler` is `AutoCloseable`: closing it stops the loop, drains the pool with a ten-second grace
period, and closes the journal.

## Sizing the pool

Two numbers, and they mean different things.

**Thread count** is how many jobs run at once. `WorkerPool.sizedForMachine` uses the processor
count, which is right when handlers are CPU-bound and much too low when they wait on a network — a
handler that spends 90% of its time waiting on HTTP wants many more threads than the machine has
cores.

**Queue size** is how much burst the pool absorbs before refusing. It is bounded on purpose: an
unbounded queue turns a backlog into an `OutOfMemoryError` two hours later, which is a far worse
failure than a refused submission at the moment the backlog starts.

A refused fire is completed as a failed attempt, so the job's retry policy decides what happens. It
is counted in `jobs.rejected` and in `WorkerPool.rejectedCount()`.

## What to watch

```java
scheduler.metrics().counterValues().forEach((name, value) -> ...);
scheduler.metrics().histogram("jobs.duration.ms");  // n=, p50<=, p99<=, max=
```

| Metric | What a rising value means |
| --- | --- |
| `jobs.rejected` | **The pool is undersized.** Jobs fired that could not run at all |
| `jobs.failed` | Jobs that exhausted their retries |
| `jobs.retried` | Attempts that failed with retries left. A burst means a shared dependency wobbled |
| `jobs.duration.ms` p99 | Handlers getting slower. Compare against the p50, not the mean |

`jobs.rejected` is the one to alert on. The others describe jobs behaving badly; that one means the
scheduler could not do its job.

Durations are reported as bucket upper bounds, so `p99<=63` means "at least 99% finished within
63ms". The over-estimate is deliberate — an approximation that is honest about its direction beats a
precise figure that is quietly wrong. See [architecture.md](architecture.md) for why buckets.

## Reading the journal

```bash
cadence replay ./cadence.journal
```

```
./cadence.journal  replayed 7 records

  2026-10-28T20:41:23.688  submitted    R0W0JR
  2026-10-28T20:41:23.704  started      R0W0JR
  2026-10-28T20:41:23.711  failed       R1N0JS
  2026-10-28T20:41:23.712  succeeded    R0W0JR
```

The short id is the last six characters, which carry the entropy. Full ids sort by creation time, so
`sort` on a journal is chronological.

Exit codes: `0` for a clean replay, `1` for a damaged one. A journal that does not exist replays as
zero records and exits `0` — that is a first start, not a failure.

### When a replay reports damage

```
replayed 412 records, stopped at damage: line 414: checksum a3f9c012 does not match the record's 7b2e4d81
```

The bytes changed after they were written, which means the storage is at fault rather than the
process. Replay stops there because the records after it cannot be vouched for either. The 412
records before it are intact and usable.

```
replayed 412 records, dropped an incomplete final record
```

Different thing, and ordinary: the process was killed between the write and the flush. Nothing is
lost but the last event, and the job it described will fire again.

### Compaction

Replay is linear in the journal's length, so startup time grows with history. `Journal.rewrite`
writes a chosen set of records to a temporary file and renames it into place, which is atomic — a
crash leaves either the old journal or the new one.

Nothing calls it on a schedule. Doing it periodically is left to the host, because "which records
still matter" depends on what the host wants to be able to reconstruct.

## Durability

`Journal.open(path, true)` forces each record to disk before returning. It is off by default:

| | Default | `syncOnAppend` |
| --- | --- | --- |
| Record survives a process crash | yes | yes |
| Record survives a power cut | no | yes |
| Records per second | thousands | a few hundred |

Every append flushes regardless, so a record is on the operating system's side of the fence the
moment `append` returns — losing one needs the machine to go down, not the process. The default
trades a power cut's last few records for two orders of magnitude of throughput, on the grounds that
the cost is a repeated job run, and handlers are expected to tolerate that anyway.

If they are not idempotent, turn it on. If they are not idempotent and you cannot afford the
throughput, the honest answer is that this scheduler does not give exactly-once execution and
nothing configured here will.

## Shutdown

```java
scheduler.close();                       // stop, drain for 10s, close the journal
pool.shutdown(Duration.ofSeconds(30));   // or drain for longer
```

`shutdown` stops accepting work, lets what is queued finish up to the deadline, and only then
interrupts. A handler that ignores interruption outlives the call; there is no way to stop it, and
`JobHandler` says so.

An idle pool shuts down in well under a second, because workers poll with a timeout rather than
blocking in `take()`.

## Things that will surprise you

**A failing recurring job stays scheduled.** A nightly report that failed tonight still runs
tomorrow. Retiring it on failure would mean one bad night stops it for ever.

**Cancelling does not stop a running job.** `cancel` returns `false` for a job already in
`RUNNING`. It stops a job from starting.

**A settled job stays in the table.** `SUCCEEDED`, `FAILED` and `CANCELLED` jobs remain visible to
`scheduler.jobs()` after leaving the queue, so they can still be inspected. For a long-lived
scheduler submitting many one-shot jobs, that table only grows.

**The scheduling thread is a single point of failure.** If it dies, nothing fires and the only
signal is `isRunning()` returning false. Worth watching if the host has somewhere to report it.

**Handler timeouts are not enforced.** `JobSpec.timeout` is accepted, validated and readable, and
that is all — the scheduler does not interrupt a handler that exceeds it, and the value is not
written to the journal either. A long-running handler holds its worker until it finishes. The field
is there because the shape of the API needs it and enforcing it needs a watchdog that does not
exist yet; until then, treat it as documentation of intent rather than a limit.
