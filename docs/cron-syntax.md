# Cron syntax

## The five fields

```
  ┌────────── minute       0-59
  │ ┌──────── hour         0-23
  │ │ ┌────── day-of-month 1-31
  │ │ │ ┌──── month        1-12 or JAN-DEC
  │ │ │ │ ┌── day-of-week  0-6  or SUN-SAT
  │ │ │ │ │
  * * * * *
```

Fields are separated by any run of whitespace, so an expression pasted out of an aligned crontab
works unchanged.

## Forms

| Form | Meaning | Example |
| --- | --- | --- |
| `*` | every value | `* * * * *` — every minute |
| `n` | exactly that value | `30 9 * * *` — 09:30 daily |
| `a-b` | a through b, inclusive | `0 9-17 * * *` — on the hour, 9am to 5pm |
| `a,b,c` | any of these | `0,15,30,45 * * * *` — every quarter hour |
| `*/n` | every nth from the field's minimum | `*/15 * * * *` — :00, :15, :30, :45 |
| `a-b/n` | every nth within the range | `0 9-17/2 * * *` — 9, 11, 13, 15, 17 |
| `a/n` | every nth from a to the field's end | `10/15 * * * *` — :10, :25, :40, :55 |
| `JAN`…`DEC` | a month by name | `0 0 1 JAN *` — new year |
| `SUN`…`SAT` | a weekday by name | `0 9 * * MON-FRI` — weekday mornings |

Names are case-insensitive. Lists may mix forms: `0-2,30,40/10` is minutes 0, 1, 2, 30, 40, 50.

## Macros

| Macro | Expands to |
| --- | --- |
| `@yearly`, `@annually` | `0 0 1 1 *` |
| `@monthly` | `0 0 1 * *` |
| `@weekly` | `0 0 * * 0` |
| `@daily`, `@midnight` | `0 0 * * *` |
| `@hourly` | `0 * * * *` |

A macro keeps its own text, so a schedule stored as `@daily` reads back as `@daily` rather than as
its expansion.

## Things worth knowing

### The two day fields are unioned, not intersected

This is cron's one genuinely surprising rule, and the only one worth memorising.

When **both** day-of-month and day-of-week are restricted, a day matches if **either** matches:

```
0 0 1 * MON     fires on the 1st of every month AND on every Monday
```

Not "Mondays that fall on the 1st" — that would match about once a year, and a field pair that
almost never matches would be useless. When only one of the two is restricted, the other places no
constraint and the result is just that field.

`cadence validate` says so out loud whenever it sees both fields restricted, because the rule
surprises people and the moment they are checking a schedule is the moment to mention it.

### A full range is not a wildcard

`*` and `0-6` match the same seven weekdays, but they are not interchangeable:

```
0 0 15 * *      the 15th of the month
0 0 15 * 0-6    every day, because 0-6 is "restricted" and gets unioned
```

The second is almost certainly not what anyone wants, and it follows directly from the union rule.
Write `*` when you mean "no constraint".

### Sunday is both 0 and 7

Both conventions appear in working crontabs, so both are accepted and 7 is normalised to 0.
Rejecting one would reject expressions copied from a system that uses it.

### A descending range is refused

`FRI-MON` is an error, not a range that wraps around the week. Some cron implementations wrap and
some do not, so guessing would make the same expression behave differently here than wherever it
was copied from. The error says to write two entries: `FRI-SAT,SUN-MON`.

### There are no seconds

Five fields, not six. A scheduler that fires on the second invites schedules it cannot honour: the
queue operation, the journal write and the handoff to a worker all cost more than the interval does.
The field would be there to be misused rather than used.

### A schedule can be legal and impossible

`0 0 30 2 *` — the 30th of February — parses fine and never fires. `nextAfter` searches eight years
ahead and then reports that there is nothing, rather than looping to the end of time.

Eight years rather than one because `0 0 29 2 *` is legal and *does* fire. Leap years are usually
four apart, but a century boundary stretches the gap to eight, so a shorter horizon would report
"never" for a schedule that works.

`cadence next` prints `(no further fires within eight years)` when it hits this, and `cadence
validate` exits non-zero.

### The next fire is strictly after the instant given

`nextAfter(t)` never returns `t`, even when the schedule matches `t` exactly. The scheduler
reschedules from the time a job just fired, so an inclusive search would land on that same instant
and spin.

## Limits

| Limit | Value | Why |
| --- | --- | --- |
| Fields | exactly 5 | A count that is not 5 is a typo, and reporting it beats guessing |
| Step | 1 to the field's width | A step of 0 would never advance; a wider one is a single value |
| Search horizon | 8 years | Covers a leap day across a century boundary |
| Expression length | unbounded | It is parsed once, at submission |

## Examples

```bash
cadence next '*/15 9-17 * * MON-FRI' --count 8
cadence next '@daily' --from 2026-12-24
cadence validate '0 0 1 * MON'
```

```java
CronExpression cron = CronExpression.parse("0 9 * * MON-FRI");
Optional<LocalDateTime> next = cron.nextAfter(LocalDateTime.now());
boolean dueNow = cron.matches(LocalDateTime.now());
```
