# Design decisions

Notes for reviewers on choices that go beyond, or differ from, the original assignment and tests.

## Changes to the provided tests

Each change is in its own commit with the reason in the commit message.

- **`testCensusSingle_InvalidAge_ThrowsExceptionOrIgnores`**: the expected output was `"1:0:3"` (colon), which
  doesn't match `OUTPUT_FORMAT` (`Position:Age=Total`). Changed to `"1:0=3"`, `"2:1=2"`, `"3:2=1"`.
- **`testCensusSingle_10_000_people_valid`**: ages 90 and 106 both have 84 people, so they share 3rd place, but only
  age 90 was expected. Added `"3:106=84"` to be consistent with how ties at 1st and 2nd place are treated in the other
  tests. Also corrected the helper comment: ages 10 and 35 total 85, not 84.
- **`testCensusSingle_Exception_HandlesExceptions`**: its iterator is empty, so the throwing `next()` is only reached
  by code that calls `next()` without checking `hasNext()`. Kept it, but changed its failure message from "Exceptions
  aren't being treated." to "next() was called without checking hasNext() first.", because the old message suggested
  exceptions should be swallowed, which conflicts with the fail-fast policy below.
- **`testCensusMultiple_FailToCreate1_ClosesAllIterators`**: expected a region the factory can't open to be skipped
  and a result returned. Under the fail-fast policy below it now expects a `CensusException` naming the region. Its
  "all iterators closed" check was removed: the failing region is first in the list, so no other iterator is ever
  opened and there is nothing to close.
- **`testCensusMultiple_FailToReturn1Item_ClosesAllIterators`**: as provided, it never exercised a failing
  iterator. Its `failsOn1` region wasn't in the requested list, and its data was empty, so the throwing `next()`
  could never be reached. It also duplicated `FailToCreate1` by including the `"invalid"` region. Now `failsOn1`
  has one item and sits in the middle of the list, and the test checks for a `CensusException` naming it and that
  it and the regions read before it were closed. Method names are kept as provided.
- **`@DisplayName`** added to every test to describe what it checks.

## Ranking

- **"Top 3" means the top 3 distinct totals** (dense ranking). Ages with equal totals share a position and the next
  total takes the next position: totals 93, 85, 85, 84 are positions 1, 2, 2, 3. So more than 3 ages can be returned.
- **Tied ages are listed youngest first.**
- Implemented by sorting the ages with a `Comparator` (total descending, then age ascending) and walking the sorted
  list once in a plain loop. A stream-only version was tried but was harder to read. The sort is over at most 151
  ages, never over people, so its cost doesn't depend on population size.

## Counting

- **Ages are counted into a `long[]` histogram** (index = age) instead of a `Map`. There's no hashing or boxing per
  record, and the array is small enough to stay in CPU cache. `long` avoids overflow when totals are summed across many
  regions.
- **Valid ages are `0..150` (`MAX_AGE`). Nulls and ages outside that range are skipped**; the invalid-age test allows
  either skipping or throwing.

## Exception handling: fail fast, never silently ignore

Any failure to read a region fails the whole call. Returning totals that silently leave out part of the requested
data would be a wrong answer, so the caller is always told.

- The factory throws, or returns `null` → `CensusException` naming the region.
- `hasNext()` / `next()` / `close()` throws while reading → `CensusException` naming the region, with the original
  exception as the cause.
- Every failure is logged at ERROR (`System.Logger`, no extra dependency) before it is thrown.
- **An iterator that was opened is always closed** (try-with-resources), including on failure.
- **Multiple regions: stop at the first failure.** The remaining regions are not read. Reading a healthy region costs
  time proportional to its population, and once one region has failed the result is already known to be a failure.
  Reporting every failing region instead would rarely help: failures while reading are typically I/O errors, which
  can't be fixed by looking at the exception, so the next step (fix the environment, retry) is the same either way.
- **A region with no population is not an error.** A region where nobody lives (e.g. a hot desert) is expected to
  come back as an *empty iterator*: the single-region method returns an empty result and the multi-region method
  adds nothing to the totals.
- **`null` from the factory is still a failure.** It could mean "nobody lives here", but it could equally mean "the
  region couldn't be found or opened" (e.g. a mistyped region name). Treating it as "no population" would turn such
  mistakes into a silently wrong answer, whereas an empty iterator says "no data" unambiguously.

## Thread safety

- **`Census` holds no mutable state.** Its only field is the `final` factory, and each call counts into its own local
  histogram, so concurrent calls don't share anything they write to.
- **Iterators are not assumed to be unique per call.** The factory is supplied from outside and may return the same
  iterator instance to several callers (the test factory does). Iterators aren't thread safe, so each call holds
  **the iterator's own lock (`synchronized (iterator)`) while reading and closing it**. Callers with different
  iterators never block each other; callers sharing one take turns, so every record is counted exactly once.
- **Closing happens inside the lock**, so the next caller always sees the iterator already closed. It never sees a
  half-finished state where the iterator is drained but not yet closed.
- **A shared iterator can only be read once.** The first caller gets the full result. A later caller finds it closed
  and gets a `CensusException`, consistent with the fail-fast policy. Letting both callers get the full data would
  mean caching results inside `Census`, which would make it stateful; that's out of scope.
- A lock stored on `Census` (e.g. a map from iterator to lock) was rejected because it adds shared mutable state.
  Locking the object itself is the standard JDK pattern (compare `Collections.synchronizedList`).
