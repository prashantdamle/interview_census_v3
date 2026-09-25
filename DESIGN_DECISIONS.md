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
- Design note: a factory should ideally return an *empty iterator* for a region with no data rather than throw or
  return `null`. `Census` doesn't control the factory, though, so it treats both as failures.
