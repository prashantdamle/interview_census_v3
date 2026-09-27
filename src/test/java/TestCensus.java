import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class TestCensus {
    private static Function<String, Census.AgeInputIterator> factory = TestCensus::iteratorForRegion;
    private static Census census = new Census(factory);
    private static Map<String, Census.AgeInputIterator> createdIterators = new HashMap<>();

    @Test
    @DisplayName("Creating a Census without a factory fails straight away, not later as a region failure")
    public void testCensus_NullFactory_ThrowsNullPointerException() {
        Assertions.assertThrows(NullPointerException.class, () -> new Census(null));
    }

    @Test
    @DisplayName("Single region the factory fails to open (throws) throws CensusException naming the region")
    public void testCensusSingle_FactoryThrows_ThrowsCensusException() {
        CensusException e = Assertions.assertThrows(CensusException.class, () -> census.top3Ages("unknownRegion"));
        Assertions.assertTrue(e.getMessage().contains("unknownRegion"), "Message doesn't name the region.");
    }

    @Test
    @DisplayName("Single region the factory fails to open (returns null) throws CensusException naming the region")
    public void testCensusSingle_FactoryReturnsNull_ThrowsCensusException() {
        Census nullFactoryCensus = new Census(region -> null);
        CensusException e = Assertions.assertThrows(CensusException.class, () -> nullFactoryCensus.top3Ages("anyRegion"));
        Assertions.assertTrue(e.getMessage().contains("anyRegion"), "Message doesn't name the region.");
    }

    @Test
    @DisplayName("Single region with no data returns an empty array (not null) and closes the iterator")
    public void testCensusSingle_EmptyInput_ClosesIterator() {
        AgeIteratorWrapper iterator =
                registerIterator(new AgeIteratorWrapper(Collections.emptyIterator(), "empty"));
        String[] strings = census.top3Ages("empty");
        Assertions.assertTrue(iterator.closed, "Iterator hasn't been closed.");
        Assertions.assertTrue(strings != null, "Invalid result null.");
        System.out.println(Arrays.toString(strings));
        Assertions.assertArrayEquals(new String[]{}, strings);
    }

    @Test
    @DisplayName("Single region with one age returns one entry in Position:Age=Total format")
    public void testCensusSingle_1Age_Success() {
            registerIterator(new AgeIteratorWrapper(ImmutableList.of(1).iterator(), "1item"));
        String[] strings = census.top3Ages("1item");
        System.out.println(Arrays.toString(strings));
        Assertions.assertArrayEquals(new String[]{"1:1=1"}, strings);
    }

    @Test
    @DisplayName("Output uses ASCII digits whatever the JVM's default locale")
    public void testCensusSingle_NonEnglishLocale_UsesAsciiDigits() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG")); // formats numbers with Arabic-Indic digits
            registerIterator(new AgeIteratorWrapper(ImmutableList.of(35, 35).iterator(), "arabicLocale"));
            Assertions.assertArrayEquals(new String[]{"1:35=2"}, census.top3Ages("arabicLocale"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("Single region with an empty iterator whose next() throws: next() is never called because "
            + "hasNext() is checked first, and the iterator is closed")
    public void testCensusSingle_Exception_HandlesExceptions() {
        AgeIteratorWrapper iterator =
                registerIterator(new AgeIteratorWrapper(Collections.emptyIterator(), "exception") {
                    @Override
                    public Integer next() {
                        throw new RuntimeException("Fake exception");
                    }
                });
        try {
            census.top3Ages("exception");
        } catch (RuntimeException e) {
            Assertions.fail("next() was called without checking hasNext() first.");
        } finally {
            Assertions.assertTrue(iterator.closed, "Iterator hasn't been closed.");
        }
    }

    @Test
    @DisplayName("Single region with a negative age either ignores it and ranks the valid ages, "
            + "or throws a RuntimeException; the iterator is closed either way")
    public void testCensusSingle_InvalidAge_ThrowsExceptionOrIgnores() {
        AgeIteratorWrapper iterator =
                registerIterator(new AgeIteratorWrapper(ImmutableList.of(0, 0, 0, 1, 1, 2, -1).iterator(), "invalidAge"));
        try {
            String[] strings = census.top3Ages("invalidAge");
            System.out.println("Invalid ages ignored. Good one!");
            Assertions.assertTrue(iterator.closed, "Iterator hasn't been closed.");
            Assertions.assertTrue(strings != null, "Invalid result null.");
            System.out.println(Arrays.toString(strings));
            Assertions.assertArrayEquals(new String[]{"1:0=3", "2:1=2", "3:2=1"}, strings);
        } catch (RuntimeException e) {
            System.out.println("Invalid ages throw exception. Not bad!");
        } finally {
            Assertions.assertTrue(iterator.closed, "Iterator hasn't been closed.");
        }
    }

    @Test
    @DisplayName("Single region with 10,000 people: equal totals share a position (dense ranking), "
            + "tied ages are in ascending order, and top 3 means the top 3 distinct totals")
    public void testCensusSingle_10_000_people_valid() {
        AgeIteratorWrapper iterator =
                registerIterator(new AgeIteratorWrapper(newPseudoRandomIterator(10_000), "10_000"));
        String[] strings = census.top3Ages("10_000");
        Assertions.assertTrue(iterator.closed, "Iterator hasn't been closed.");
        Assertions.assertTrue(strings != null, "Invalid result null.");
        System.out.println(Arrays.toString(strings));
        Assertions.assertArrayEquals(new String[]{"1:138=93", "2:10=85", "2:35=85", "3:90=84", "3:106=84"}, strings);
    }

    @Test
    @DisplayName("Single region whose next() fails mid-read throws CensusException naming the region and closes the iterator")
    public void testCensusSingle_NextThrowsMidRead_ThrowsCensusException() {
        AgeIteratorWrapper iterator =
                registerIterator(new AgeIteratorWrapper(ImmutableList.of(1, 2, 3).iterator(), "nextFails") {
                    private int calls = 0;

                    @Override
                    public Integer next() {
                        if (++calls == 2) {
                            throw new RuntimeException("Fake read failure");
                        }
                        return super.next();
                    }
                });
        CensusException e = Assertions.assertThrows(CensusException.class, () -> census.top3Ages("nextFails"));
        Assertions.assertTrue(e.getMessage().contains("nextFails"), "Message doesn't name the region.");
        Assertions.assertTrue(iterator.closed, "Iterator hasn't been closed.");
    }

    @Test
    @DisplayName("Single region whose hasNext() fails mid-read throws CensusException naming the region and closes the iterator")
    public void testCensusSingle_HasNextThrowsMidRead_ThrowsCensusException() {
        AgeIteratorWrapper iterator =
                registerIterator(new AgeIteratorWrapper(ImmutableList.of(1, 2, 3).iterator(), "hasNextFails") {
                    private int calls = 0;

                    @Override
                    public boolean hasNext() {
                        if (++calls == 2) {
                            throw new RuntimeException("Fake read failure");
                        }
                        return super.hasNext();
                    }
                });
        CensusException e = Assertions.assertThrows(CensusException.class, () -> census.top3Ages("hasNextFails"));
        Assertions.assertTrue(e.getMessage().contains("hasNextFails"), "Message doesn't name the region.");
        Assertions.assertTrue(iterator.closed, "Iterator hasn't been closed.");
    }

    @Test
    @DisplayName("Two threads given the same iterator by the factory take turns: one reads all the data, "
            + "the other finds the iterator closed and gets CensusException")
    public void testCensusSingle_SharedIterator_ReadByOneThreadOnly() throws InterruptedException {
        Iterator<Integer> slowAges = IntStream.range(0, 400).map(e -> {
            try {
                Thread.sleep(1); // slow enough that both threads are reading at the same time
            } catch (InterruptedException e1) {
                // ignore
            }
            return e % 4;
        }).iterator();
        registerIterator(new AgeIteratorWrapper(slowAges, "shared"));

        CyclicBarrier bothReady = new CyclicBarrier(2);
        Callable<String[]> call = () -> {
            bothReady.await();
            return census.top3Ages("shared");
        };

        List<String[]> results = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (Future<String[]> future : pool.invokeAll(List.of(call, call))) {
                try {
                    results.add(future.get());
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        }

        Assertions.assertEquals(1, results.size(), "Exactly one caller should read the data.");
        Assertions.assertArrayEquals(new String[]{"1:0=100", "1:1=100", "1:2=100", "1:3=100"}, results.get(0));
        Assertions.assertEquals(1, failures.size(), "Exactly one caller should fail.");
        Assertions.assertInstanceOf(CensusException.class, failures.get(0));
    }

    @Test
    @DisplayName("Multiple empty regions return a non-null result and close every iterator")
    public void testCensusMultiple_Empty_ClosesAllIterators() {
        List<AgeIteratorWrapper> iterators =
                IntStream.range(0, 5)
                        .mapToObj(e -> registerIterator(new AgeIteratorWrapper(Collections.emptyIterator(), "empty" + e)))
                        .collect(Collectors.toList());

        String[] strings = census.top3Ages(iterators.stream().map(e -> e.region).collect(Collectors.toList()));
        Assertions.assertFalse(iterators.stream().anyMatch(e -> !e.closed), "Iterator hasn't been closed.");
        Assertions.assertTrue(strings != null, "Invalid result null.");
    }

    @Test
    @DisplayName("Multiple regions where the factory fails for the first one: throws CensusException naming the region")
    public void testCensusMultiple_FailToCreate1_ClosesAllIterators() {
        List<AgeIteratorWrapper> iterators =
                IntStream.range(0, 5)
                        .mapToObj(e -> registerIterator(new AgeIteratorWrapper(Collections.emptyIterator(), "empty" + e)))
                        .collect(Collectors.toList());

        List<String> regions = Stream.concat(Stream.of("invalid"), iterators.stream().map(e -> e.region)).collect(Collectors.toList());
        CensusException e = Assertions.assertThrows(CensusException.class, () -> census.top3Ages(regions));
        Assertions.assertTrue(e.getMessage().contains("invalid"), "Message doesn't name the region.");
    }

    @Test
    @DisplayName("Multiple regions where one in the middle fails to return an item: throws CensusException naming it, "
            + "and it and the regions read before it are closed")
    public void testCensusMultiple_FailToReturn1Item_ClosesAllIterators() {
        List<AgeIteratorWrapper> iterators =
                IntStream.range(0, 5)
                        .mapToObj(e -> registerIterator(new AgeIteratorWrapper(Collections.emptyIterator(), "empty" + e)))
                        .collect(Collectors.toList());

        // Has one item, so next() is actually called and fails.
        AgeIteratorWrapper failsOn1 = registerIterator(new AgeIteratorWrapper(ImmutableList.of(1).iterator(), "failsOn1") {
            @Override
            public Integer next() {
                throw new RuntimeException("Couldn't return item");
            }
        });

        // Regions after failsOn1 are never opened (fail fast).
        List<String> regions = List.of("empty0", "empty1", "failsOn1", "empty2", "empty3", "empty4");

        CensusException e = Assertions.assertThrows(CensusException.class, () -> census.top3Ages(regions));
        Assertions.assertTrue(e.getMessage().contains("failsOn1"), "Message doesn't name the region.");
        Assertions.assertTrue(failsOn1.closed, "Failing iterator hasn't been closed.");
        Assertions.assertTrue(iterators.get(0).closed && iterators.get(1).closed,
                "An iterator read before the failure hasn't been closed.");
    }

    @Test
    @DisplayName("15 regions: totals are summed across regions before ranking "
            + "(~1 ms per element, so this is where processing regions in parallel pays off)")
    public void testCensusMultiple_15X10_000_regions_Success() {
        List<AgeIteratorWrapper> iterators =
                IntStream.range(0, 15)
                        .mapToObj(e -> registerIterator(new AgeIteratorWrapper(newPseudoRandomIterator(e * 10 + 1000), "multiple15_" + e)))
                        .collect(Collectors.toList());

        String[] strings = census.top3Ages(iterators.stream().map(e -> e.region).collect(Collectors.toList()));
        Assertions.assertFalse(iterators.stream().anyMatch(e -> !e.closed), "Iterator hasn't been closed.");
        Assertions.assertTrue(strings != null, "Invalid result null.");
        System.out.println(Arrays.toString(strings));
        Assertions.assertArrayEquals(new String[]{"1:32=254", "2:53=217", "3:123=213"}, strings);
    }

    @Test
    @DisplayName("Three ages tie for 1st and the next age is 2nd, not 4th (dense ranking); "
            + "with only two distinct totals there is no 3rd position")
    public void testCensusMultiple_1X1000_regions_share_place_Success() {
        PrimitiveIterator.OfInt iterator = IntStream.range(0, 9999)
                .map(e -> e % 4)
                .iterator();
        List<AgeIteratorWrapper> iterators =
                IntStream.range(0, 1)
                        .mapToObj(e -> registerIterator(new AgeIteratorWrapper(iterator, "share_place" + e)))
                        .collect(Collectors.toList());

        String[] strings = census.top3Ages(iterators.stream().map(e -> e.region).collect(Collectors.toList()));
        Assertions.assertFalse(iterators.stream().anyMatch(e -> !e.closed), "Iterator hasn't been closed.");
        Assertions.assertTrue(strings != null, "Invalid result null.");
        System.out.println(Arrays.toString(strings));
        Assertions.assertArrayEquals(new String[]{"1:0=2500", "1:1=2500", "1:2=2500", "2:3=2499"}, strings);
    }

    @Test
    @DisplayName("An empty list of regions returns an empty result")
    public void testCensusMultiple_EmptyList_ReturnsEmpty() {
        Assertions.assertArrayEquals(new String[]{}, census.top3Ages(List.of()));
    }

    @Test
    @DisplayName("Regions are read in parallel, on no more threads than there are cores")
    public void testCensusMultiple_ReadsRegionsInParallel() {
        int cores = Runtime.getRuntime().availableProcessors();
        Assumptions.assumeTrue(cores > 1, "Reading in parallel needs more than one core.");

        AtomicInteger readingNow = new AtomicInteger();
        AtomicInteger mostReadingAtOnce = new AtomicInteger();
        // Twice as many regions as cores, so the pool is kept full.
        List<String> regions = IntStream.range(0, cores * 2)
                .mapToObj(i -> registerIterator(new AgeIteratorWrapper(Collections.nCopies(50, 30).iterator(), "parallel" + i) {
                    @Override
                    public Integer next() {
                        mostReadingAtOnce.accumulateAndGet(readingNow.incrementAndGet(), Math::max);
                        try {
                            sleepOneMillisecond();
                            return super.next();
                        } finally {
                            readingNow.decrementAndGet();
                        }
                    }
                }).region)
                .collect(Collectors.toList());

        census.top3Ages(regions);

        Assertions.assertTrue(mostReadingAtOnce.get() > 1, "Regions weren't read in parallel.");
        Assertions.assertTrue(mostReadingAtOnce.get() <= cores,
                "More regions were read at once (" + mostReadingAtOnce.get() + ") than there are cores (" + cores + ").");
    }

    @Test
    @DisplayName("When one region fails, the other regions stop early instead of reading all their data")
    public void testCensusMultiple_FailingRegion_StopsOtherRegions() {
        AgeIteratorWrapper failing = registerIterator(new AgeIteratorWrapper(ImmutableList.of(1).iterator(), "failsFirst") {
            @Override
            public Integer next() {
                throw new RuntimeException("Couldn't return item");
            }
        });
        AtomicInteger slowRecordsRead = new AtomicInteger();
        // Reading all 3000 records would take ~3 seconds.
        AgeIteratorWrapper slow = registerIterator(new AgeIteratorWrapper(Collections.nCopies(3000, 30).iterator(), "slow3000") {
            @Override
            public Integer next() {
                sleepOneMillisecond();
                slowRecordsRead.incrementAndGet();
                return super.next();
            }
        });

        // The failing region is first, so even with a single thread the slow region is never read in full.
        CensusException e = Assertions.assertThrows(CensusException.class,
                () -> census.top3Ages(List.of("failsFirst", "slow3000")));
        Assertions.assertTrue(e.getMessage().contains("failsFirst"), "Message doesn't name the region.");
        Assertions.assertTrue(failing.closed, "Failing iterator hasn't been closed.");
        Assertions.assertTrue(slowRecordsRead.get() < 3000, "The slow region was read in full.");
        Assertions.assertTrue(slowRecordsRead.get() == 0 || slow.closed, "The slow iterator was read but not closed.");
    }

    @Test
    @DisplayName("When one region fails, a region blocked in a long read is interrupted instead of being waited for")
    public void testCensusMultiple_FailingRegion_InterruptsBlockedRegion() {
        Assumptions.assumeTrue(Runtime.getRuntime().availableProcessors() > 1, "Needs both regions read at once.");

        CountDownLatch blockedIsReading = new CountDownLatch(1);
        AtomicBoolean blockedWasInterrupted = new AtomicBoolean();
        registerIterator(new AgeIteratorWrapper(ImmutableList.of(1).iterator(), "blocked") {
            @Override
            public Integer next() {
                blockedIsReading.countDown();
                try {
                    Thread.sleep(10_000); // stands in for a hung network read
                } catch (InterruptedException e) {
                    blockedWasInterrupted.set(true);
                    throw new RuntimeException("Read interrupted", e);
                }
                return super.next();
            }
        });
        registerIterator(new AgeIteratorWrapper(ImmutableList.of(1).iterator(), "failsWhileOtherIsBlocked") {
            @Override
            public Integer next() {
                try {
                    blockedIsReading.await(5, TimeUnit.SECONDS); // fail only once the other region is blocked
                } catch (InterruptedException e) {
                    // ignore
                }
                throw new RuntimeException("Couldn't return item");
            }
        });

        CensusException e = Assertions.assertThrows(CensusException.class,
                () -> census.top3Ages(List.of("blocked", "failsWhileOtherIsBlocked")));
        Assertions.assertTrue(e.getMessage().contains("failsWhileOtherIsBlocked"), "Message doesn't name the region.");
        Assertions.assertTrue(blockedWasInterrupted.get(), "The blocked region was waited for instead of interrupted.");
    }

    // HELPER METHODS

    private static void sleepOneMillisecond() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            // ignore
        }
    }

    private Iterator<Integer> newPseudoRandomIterator(int n) {
        Random random = new Random(1000);
        return IntStream.range(0, n).map(e -> {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e1) {
                // ignore
            }
            if (e < 1) {
                return 35; // Just so we have 10 and 35 as 85 in total.
            }
            return Math.abs(random.nextInt() % 150);
        }).iterator();
    }

    private AgeIteratorWrapper registerIterator(AgeIteratorWrapper iterator) {
        createdIterators.put(iterator.region, iterator);
        return iterator;
    }

    private static Census.AgeInputIterator iteratorForRegion(String region) {
        return Optional.ofNullable(createdIterators.get(region))
                .orElseThrow(() -> new RuntimeException("Couldn't find region " + region));
    }

    private class AgeIteratorWrapper implements Census.AgeInputIterator {
        private boolean closed;
        private String region;
        private Iterator<Integer> delegate;

        private AgeIteratorWrapper(Iterator<Integer> delegate, String region) {
            this.delegate = delegate;
            this.region = region;
            this.closed = false;
        }

        @Override
        public void close() throws IOException {
            this.closed = true;
        }

        @Override
        public boolean hasNext() {
            if (closed) {
                throw new IllegalStateException("Iterator is closed.");
            }

            return delegate.hasNext();
        }

        @Override
        public Integer next() {
            if (closed) {
                throw new IllegalStateException("Iterator is closed.");
            }

            return delegate.next();
        }
    }
}
