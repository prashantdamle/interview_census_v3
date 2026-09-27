import java.io.Closeable;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * Implement the two methods below. We expect this class to be stateless and thread safe.
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
        this.iteratorFactory = iteratorFactory;
    }

    /**
     * Given one region name, call {@link #iteratorFactory} to get an iterator for this region and return
     * the 3 most common ages in the format specified by {@link #OUTPUT_FORMAT}.
     */
    public String[] top3Ages(String region) {
        return toTop3(countRegion(region));
    }

    /**
     * Given a list of region names, call {@link #iteratorFactory} to get an iterator for each region and return
     * the 3 most common ages across all regions in the format specified by {@link #OUTPUT_FORMAT}.
     * We expect you to make use of all cores in the machine, specified by {@link #CORES).
     */
    public String[] top3Ages(List<String> regionNames) {
        Objects.requireNonNull(regionNames, "regionNames");
        long[] totals = new long[MAX_AGE + 1];
        for (String region : regionNames) {
            long[] counts = countRegion(region);
            for (int age = 0; age <= MAX_AGE; age++) {
                totals[age] += counts[age];
            }
        }
        return toTop3(totals);
    }

    /**
     * Counts the ages of one region into a histogram (index = age, value = number of people).
     *
     * @throws CensusException if the region cannot be opened, or fails while being read or closed. The iterator is
     *                         always closed once opened.
     */
    private long[] countRegion(String region) {
        AgeInputIterator iterator = openRegion(region);
        long[] counts = new long[MAX_AGE + 1];
        // The factory may hand the same iterator to several callers. Hold its lock while reading and closing it,
        // so callers take turns instead of interleaving hasNext()/next() calls on an iterator that isn't thread safe.
        synchronized (iterator) {
            try (iterator) {
                while (iterator.hasNext()) {
                    Integer age = iterator.next();
                    if (isValidAge(age)) {
                        counts[age]++;
                    }
                }
            } catch (IOException | RuntimeException e) {
                throw failure("Failed to read region " + region, e);
            }
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
            result.add(String.format(OUTPUT_FORMAT, position, ageCount.age(), ageCount.count()));
        }
        return result.toArray(String[]::new);
    }

    /**
     * An age and the number of people with that age.
     */
    private record AgeCount(int age, long count) {
    }

    /**
     * Implementations of this interface will return ages on call to {@link Iterator#next()}. They may open resources
     * when being instantiated created.
     */
    public interface AgeInputIterator extends Iterator<Integer>, Closeable {
    }
}
