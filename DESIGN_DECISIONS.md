# Design decisions

## Read this first

### 1. Fail fast: never return a silently wrong answer

Any failure to read a region fails the whole call with a `CensusException` naming the region: the factory throws or
returns `null`, or `hasNext()`, `next()` or `close()` throws. The failure is logged at ERROR, and every iterator that
was opened is closed. With several regions, the first failure stops the rest. Reading them would take time
proportional to their population, only to discard the result.

**Why:** totals that silently leave out part of the requested data are wrong. The provided tests expected a region
that can't be opened to be skipped, so they were changed (see 4).

### 2. "Top 3" means the top 3 distinct totals (dense ranking)

Ages with equal totals share a position, so more than 3 ages can be returned: totals 93, 85, 85, 84 are positions
1, 2, 2, 3. Tied ages are listed youngest first. This is what the provided tests expect.

### 3. Regions are read in parallel

- A fixed pool of `min(CORES, number of regions)` threads, created per call and closed with try-with-resources, so
  `Census` stays stateless. One task per region; a single region's iterator can't be split.
- Each task counts into its own `long[]` histogram, and the calling thread adds them up. There is no shared counter
  for threads to contend on.
- On the first failure the other regions are stopped with a per-call flag, checked before each record, and with
  interrupts (`shutdownNow()`) for regions blocked inside a read. Both are needed: the test iterators, for example,
  catch and ignore `InterruptedException`.
- **Result:** `testCensusMultiple_15X10_000_regions_Success` went from 20.6 s (sequential) to 2.8 s on 12 cores.

### 4. Changes to the provided tests

Each change is in its own commit. Method names are unchanged.

| Test | Change | Why |
|---|---|---|
| `testCensusSingle_InvalidAge_ThrowsExceptionOrIgnores` | Expected `"1:0=3"` instead of `"1:0:3"` (same for the other entries) | Typo: didn't match `OUTPUT_FORMAT` (`Position:Age=Total`) |
| `testCensusSingle_10_000_people_valid` | Added `"3:106=84"` | Ages 90 and 106 both have 84 people, so both are 3rd, as ties are treated elsewhere. Also fixed the helper comment (85, not 84). |
| `testCensusSingle_Exception_HandlesExceptions` | Failure message only | Its iterator is empty, so it really checks that `next()` isn't called without `hasNext()`. The old message ("Exceptions aren't being treated.") implied exceptions should be swallowed. |
| `testCensusMultiple_FailToCreate1_ClosesAllIterators` | Expects `CensusException`; "all iterators closed" check removed | Fail fast (1). Whether the other regions are opened at all now depends on timing. |
| `testCensusMultiple_FailToReturn1Item_ClosesAllIterators` | `failsOn1` added to the region list and given one item; `"invalid"` removed; checks `CensusException` and that the failing iterator is closed | As provided, it never read a failing iterator: `failsOn1` wasn't requested, and its empty data never reached the throwing `next()`. `"invalid"` duplicated `FailToCreate1`. |

Every test also has a `@DisplayName`. New tests cover the other decisions below.

## Other decisions

**Thread safety**
- `Census` has no mutable state: the only field is the factory, and each call works on its own local data.
- The factory may return the same iterator to several callers (the test factory does), so each call holds the
  iterator's own lock while reading and closing it. Callers sharing an iterator take turns, and every record is
  counted once. A lock map on `Census` was rejected because it would add shared state.
- Known limitation: a shared iterator can only be read once. A later caller gets a `CensusException` if the iterator
  throws once closed, but an empty result if it just reports no more data.

**Inputs**
- A region with no population (e.g. a hot desert) is an empty iterator and gives an empty result, not an error.
- `null` from the factory is a failure: it could mean "no population" or "region not found" (e.g. a typo).
- An empty region list returns an empty result. A region listed twice is counted once. The list is copied first.
- A `null` factory, region list, or region name in the list throws `NullPointerException`: a programming error, not
  a data problem.

**Counting and output**
- Ages are counted into a `long[]` indexed by age (no hashing or boxing per record). Valid ages are 0..150.
- Invalid ages (null or out of range) are skipped, which the provided test allows, with a WARNING per region giving
  the number skipped.
- Ranking sorts at most 151 ages, never people, then walks the sorted list once in a plain loop (more readable than
  the stream version that was tried).
- Output uses `Locale.ROOT`, so digits are ASCII on every machine (the default locale could print `١:٣٥=٨٥`).
- A dedicated thread pool rather than `parallelStream()`: the common pool can't be sized to `CORES` and isn't meant
  for blocking I/O.

**Coverage**
- `./gradlew jacocoTestReport` reports 99% of instructions and 97% of branches. The one line no test reaches is a
  safety net in `asCensusException`, for a region failure that is neither a `CensusException` nor an `Error`: region
  tasks wrap every other failure themselves. The path that skips a region because another region already failed is
  covered only when timing allows, so a test can't rely on it.
