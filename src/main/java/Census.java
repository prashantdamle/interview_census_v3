import java.io.Closeable;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * Finds the most common ages in census data, for one region or across several. Each region's ages come from an
 * iterator created by the factory passed to the constructor.
 * <p>
 * Stateless and thread safe: the only field is the factory, and each call counts into its own local data. If the
 * factory hands the same iterator to several callers, they take turns reading it. Any failure to read a region fails
 * the whole call with a {@link CensusException}, and every iterator that was opened is closed.
 */
public class Census {
    /**
     * Number of cores in the current machine.
     */
    private static final int CORES = Runtime.getRuntime().availableProcessors();

    /**
     * Output format expected by our tests.
     */
    public static final String OUTPUT_FORMAT = "%d:%d=%d"; // Position:Age=Total

    /**
     * Highest age counted. Ages are used as indexes into a histogram of size {@code MAX_AGE + 1}.
     */
    private static final int MAX_AGE = 150;

    /**
     * Logs failures (ERROR) and skipped invalid ages (WARNING).
     */
    private static final System.Logger LOGGER = System.getLogger(Census.class.getName());

    /**
     * Factory for iterators.
     */
    private final Function<String, Census.AgeInputIterator> iteratorFactory;

    /**
     * Creates a new Census calculator.
     *
     * @param iteratorFactory factory for the iterators.
     */
    public Census(Function<String, Census.AgeInputIterator> iteratorFactory) {
        this.iteratorFactory = Objects.requireNonNull(iteratorFactory, "iteratorFactory");
    }

    /**
     * Given one region name, call {@link #iteratorFactory} to get an iterator for this region and return
     * the 3 most common ages in the format specified by {@link #OUTPUT_FORMAT}.
     * <p>
     * Ages with the same total share a position, so more than 3 entries can be returned. A region with no people
     * returns an empty array. Invalid ages (null, or outside 0..{@value #MAX_AGE}) are skipped.
     *
     * @param region the region to count.
     * @return the most common ages, as {@code Position:Age=Total} strings, most common first.
     * @throws CensusException if the region cannot be opened, or fails while being read.
     */
    public String[] top3Ages(String region) {
        return toTop3(countRegion(region, () -> false));
    }

    /**
     * Given a list of region names, call {@link #iteratorFactory} to get an iterator for each region and return
     * the 3 most common ages across all regions in the format specified by {@link #OUTPUT_FORMAT}.
     * <p>
     * Regions are read in parallel, on up to {@link #CORES} threads. Ages with the same total share a position, as in
     * {@link #top3Ages(String)}. A region listed more than once is counted once, and an empty list returns an empty
     * array.
     *
     * @param regionNames the regions to count together.
     * @return the most common ages across all the regions, as {@code Position:Age=Total} strings, most common first.
     * @throws CensusException      if any region cannot be opened, or fails while being read. The other regions are
     *                              stopped, and every iterator that was opened is closed before this method throws.
     * @throws NullPointerException if the list, or a region name in it, is null.
     */
    public String[] top3Ages(List<String> regionNames) {
        Objects.requireNonNull(regionNames, "regionNames");
        // A copy, because the caller's list could change while we work, without duplicates, because a region
        // counts once however often it is listed.
        List<String> regions = List.copyOf(new LinkedHashSet<>(regionNames));
        if (regions.isEmpty()) {
            return new String[0];
        }

        long[] totals = new long[MAX_AGE + 1];
        // Set on the first failure, so regions still being read stop early and regions not yet started are skipped.
        AtomicBoolean failed = new AtomicBoolean();
        // Closing the pool waits for every task to finish, so all opened iterators are closed before this method
        // returns or throws.
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.min(CORES, regions.size()))) {
            CompletionService<long[]> results = new ExecutorCompletionService<>(pool);
            for (String region : regions) {
                results.submit(() -> countRegion(region, failed::get));
            }
            try {
                for (int i = 0; i < regions.size(); i++) {
                    long[] counts = results.take().get(); // in the order regions finish, not the order submitted
                    for (int age = 0; age <= MAX_AGE; age++) {
                        totals[age] += counts[age];
                    }
                }
            } catch (ExecutionException e) {
                stopRemainingRegions(failed, pool);
                throw asCensusException(e.getCause());
            } catch (InterruptedException e) {
                stopRemainingRegions(failed, pool);
                Thread.currentThread().interrupt();
                throw failure("Interrupted while counting regions", e);
            }
        }
        return toTop3(totals);
    }

    /**
     * Stops the regions that are still running after a failure. The flag stops regions between records, even if
     * their iterator ignores interrupts; the interrupt stops regions blocked inside a read. The flag is set first, so
     * an interrupted region knows it was stopped rather than failed.
     */
    private static void stopRemainingRegions(AtomicBoolean failed, ExecutorService pool) {
        failed.set(true);
        pool.shutdownNow();
    }

    /**
     * Counts the ages of one region into a histogram (index = age, value = number of people).
     *
     * @param stopRequested checked before opening the region and before each record. Once it returns true the region
     *                      is abandoned with a {@link CancellationException}.
     * @throws CensusException if the region cannot be opened, or fails while being read or closed. The iterator is
     *                         always closed once opened.
     */
    private long[] countRegion(String region, BooleanSupplier stopRequested) {
        if (stopRequested.getAsBoolean()) {
            throw new CancellationException("Skipped region " + region);
        }
        AgeInputIterator iterator = openRegion(region);
        long[] counts = new long[MAX_AGE + 1];
        long invalidAges = 0;
        boolean stopped = false;
        // The factory may hand the same iterator to several callers. Hold its lock while reading and closing it,
        // so callers take turns instead of interleaving hasNext()/next() calls on an iterator that isn't thread safe.
        synchronized (iterator) {
            try (iterator) {
                while (iterator.hasNext()) {
                    if (stopRequested.getAsBoolean()) {
                        stopped = true;
                        break;
                    }
                    Integer age = iterator.next();
                    if (isValidAge(age)) {
                        counts[age]++;
                    } else {
                        invalidAges++;
                    }
                }
            } catch (IOException | RuntimeException e) {
                if (stopRequested.getAsBoolean()) {
                    // Another region failed and this read was interrupted as a result: a normal stop, not a
                    // failure of this region.
                    throw new CancellationException("Stopped reading region " + region);
                }
                throw failure("Failed to read region " + region, e);
            }
        }
        if (stopped) {
            throw new CancellationException("Stopped reading region " + region);
        }
        if (invalidAges > 0) {
            LOGGER.log(Level.WARNING, "Skipped {0} invalid ages in region {1}", invalidAges, region);
        }
        return counts;
    }

    /**
     * @throws CensusException if the factory throws or returns null.
     */
    private AgeInputIterator openRegion(String region) {
        AgeInputIterator iterator;
        try {
            iterator = iteratorFactory.apply(region);
        } catch (RuntimeException e) {
            throw failure("Failed to open region " + region, e);
        }
        if (iterator == null) {
            throw failure("Failed to open region " + region + ": the factory returned no iterator", null);
        }
        return iterator;
    }

    /**
     * Returns the failure of a region task as a {@link CensusException}. Tasks already throw CensusException for
     * every failure they handle; anything else is unexpected and is wrapped.
     */
    private static CensusException asCensusException(Throwable cause) {
        if (cause instanceof CensusException censusException) {
            return censusException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return failure("Unexpected failure while counting regions", cause);
    }

    /**
     * Logs the failure and returns the exception for the caller to throw.
     */
    private static CensusException failure(String message, Throwable cause) {
        LOGGER.log(Level.ERROR, message, cause);
        return new CensusException(message, cause);
    }

    /**
     * Ages outside {@code 0..MAX_AGE}, and nulls, are invalid and are not counted.
     */
    private static boolean isValidAge(Integer age) {
        return age != null && age >= 0 && age <= MAX_AGE;
    }

    /**
     * Returns the ages with the 3 highest distinct totals, using dense ranking: ages with equal totals share a
     * position, and the next total takes the next position (e.g. totals 93, 85, 85, 84 are positions 1, 2, 2, 3).
     * Entries are ordered by total descending, then by age ascending.
     */
    private static String[] toTop3(long[] counts) {
        // Turn the histogram (index = age, value = total) into one AgeCount per age that has at least one person,
        // sorted most common first; ages with the same total are ordered youngest first.
        List<AgeCount> sorted = IntStream.rangeClosed(0, MAX_AGE)
                .filter(age -> counts[age] > 0)
                .mapToObj(age -> new AgeCount(age, counts[age]))
                .sorted(Comparator.comparingLong(AgeCount::count).reversed().thenComparingInt(AgeCount::age))
                .toList();

        List<String> result = new ArrayList<>();
        int position = 0;
        long previousTotal = -1;
        for (AgeCount ageCount : sorted) {
            if (ageCount.count() != previousTotal) { // a new total starts the next position
                position++;
                previousTotal = ageCount.count();
            }
            if (position > 3) {
                break;
            }
            result.add(String.format(Locale.ROOT, OUTPUT_FORMAT, position, ageCount.age(), ageCount.count()));
        }
        return result.toArray(String[]::new);
    }

    /**
     * Implementations of this interface will return ages on call to {@link Iterator#next()}. They may open resources
     * when being instantiated created.
     */
    public interface AgeInputIterator extends Iterator<Integer>, Closeable {
    }
}
