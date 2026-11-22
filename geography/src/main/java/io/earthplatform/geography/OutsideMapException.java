package io.earthplatform.geography;

/**
 * A place beyond the map itself (past the Web Mercator poles), as opposed to {@link MissingTerrainException}'s data
 * that is merely not downloaded yet. Asking again never helps: those columns belong to the Farlands.
 */
public final class OutsideMapException extends MissingTerrainException {
    private static final long serialVersionUID = 1L;
    public OutsideMapException(String message) { super(message); }
}
