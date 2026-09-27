# Census: top 3 ages

Implementation of the two `top3Ages` methods in `Census` (see `interview_census_assignment.pdf`).

## Running the tests

```
./gradlew test
```

Requires Java 19 or later (developed and tested on JDK 25).

## Where to look

- `src/main/java/Census.java`: the implementation
- `src/test/java/TestCensus.java`: the provided tests (some corrected) plus new ones
- **[DESIGN_DECISIONS.md](DESIGN_DECISIONS.md), read this first**: the key decisions, and why some provided tests
  were changed

## In short

- Any failure to read a region throws `CensusException`; there are no silently partial results.
- "Top 3" uses dense ranking: ties share a position, so more than 3 ages can be returned.
- Regions are read in parallel on up to `CORES` threads (15-region test: 20.6 s → 2.8 s).
