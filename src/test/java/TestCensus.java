import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class TestCensus {
    private static Function<String, Census.AgeInputIterator> factory = TestCensus::iteratorForRegion;
    private static Census census = new Census(factory);
    private static Map<String, Census.AgeInputIterator> createdIterators = new HashMap<>();

    @Test
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
    public void testCensusSingle_1Age_Success() {
            registerIterator(new AgeIteratorWrapper(ImmutableList.of(1).iterator(), "1item"));
        String[] strings = census.top3Ages("1item");
        System.out.println(Arrays.toString(strings));
        Assertions.assertArrayEquals(new String[]{"1:1=1"}, strings);
    }

    @Test
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
            Assertions.fail("Exceptions aren't being treated.");
        } finally {
            Assertions.assertTrue(iterator.closed, "Iterator hasn't been closed.");
        }
    }

    @Test
    public void testCensusSingle_InvalidAge_ThrowsExceptionOrIgnores() {
        AgeIteratorWrapper iterator =
                registerIterator(new AgeIteratorWrapper(ImmutableList.of(0, 0, 0, 1, 1, 2, -1).iterator(), "invalidAge"));
        try {
            String[] strings = census.top3Ages("invalidAge");
            System.out.println("Invalid ages ignored. Good one!");
            Assertions.assertTrue(iterator.closed, "Iterator hasn't been closed.");
            Assertions.assertTrue(strings != null, "Invalid result null.");
            System.out.println(Arrays.toString(strings));
            Assertions.assertArrayEquals(new String[]{"1:0:3", "2:1:2", "3:2:1"}, strings);
        } catch (RuntimeException e) {
            System.out.println("Invalid ages throw exception. Not bad!");
        } finally {
            Assertions.assertTrue(iterator.closed, "Iterator hasn't been closed.");
        }
    }

    @Test
    public void testCensusSingle_10_000_people_valid() {
        AgeIteratorWrapper iterator =
                registerIterator(new AgeIteratorWrapper(newPseudoRandomIterator(10_000), "10_000"));
        String[] strings = census.top3Ages("10_000");
        Assertions.assertTrue(iterator.closed, "Iterator hasn't been closed.");
        Assertions.assertTrue(strings != null, "Invalid result null.");
        System.out.println(Arrays.toString(strings));
        Assertions.assertArrayEquals(new String[]{"1:138=93", "2:10=85", "2:35=85", "3:90=84"}, strings);
    }

    @Test
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
    public void testCensusMultiple_FailToCreate1_ClosesAllIterators() {
        List<AgeIteratorWrapper> iterators =
                IntStream.range(0, 5)
                        .mapToObj(e -> registerIterator(new AgeIteratorWrapper(Collections.emptyIterator(), "empty" + e)))
                        .collect(Collectors.toList());

        String[] strings = census.top3Ages(Stream.concat(Stream.of("invalid"), iterators.stream().map(e -> e.region)).collect(Collectors.toList()));
        Assertions.assertFalse(iterators.stream().anyMatch(e -> !e.closed), "Iterator hasn't been closed.");
        Assertions.assertTrue(strings != null, "Invalid result null.");
    }

    @Test
    public void testCensusMultiple_FailToReturn1Item_ClosesAllIterators() {
        List<AgeIteratorWrapper> iterators =
                IntStream.range(0, 5)
                        .mapToObj(e -> registerIterator(new AgeIteratorWrapper(Collections.emptyIterator(), "empty" + e)))
                        .collect(Collectors.toList());

        registerIterator(new AgeIteratorWrapper(Collections.emptyIterator(), "failsOn1") {
            @Override
            public Integer next() {
                throw new RuntimeException("Couldn't return item");
            }
        });

        String[] strings = census.top3Ages(Stream.concat(Stream.of("invalid"), iterators.stream().map(e -> e.region)).collect(Collectors.toList()));
        Assertions.assertFalse(iterators.stream().anyMatch(e -> !e.closed), "Iterator hasn't been closed.");
        Assertions.assertTrue(strings != null, "Invalid result null.");
    }

    @Test
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

    // HELPER METHODS

    private Iterator<Integer> newPseudoRandomIterator(int n) {
        Random random = new Random(1000);
        return IntStream.range(0, n).map(e -> {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e1) {
                // ignore
            }
            if (e < 1) {
                return 35; // Just so we have 10 and 35 as 84 in total.
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
