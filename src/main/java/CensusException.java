/**
 * Thrown when a census cannot produce a correct result, e.g. because a region could not be opened or failed while
 * being read. The message names the region.
 */
public class CensusException extends RuntimeException {

    public CensusException(String message, Throwable cause) {
        super(message, cause);
    }
}
