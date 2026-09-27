/**
 * Thrown when a census cannot produce a correct result, e.g. because a region could not be opened or failed while
 * being read. The message names the region.
 */
public class CensusException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message what failed, including the region's name.
     * @param cause   the underlying failure, or null if there is none.
     */
    public CensusException(String message, Throwable cause) {
        super(message, cause);
    }
}
