package fr.geeklegend.slimeworld.storage;

import java.io.IOException;

/**
 * Where serialized worlds are kept. Implement it to use another backend (S3, Redis...).
 * <p>
 * Methods are called from the manager's IO threads, possibly concurrently for different ids,
 * never concurrently for the same id.
 */
public interface Storage {

    /**
     * @param id world id
     * @return the saved bytes, or null if the world does not exist
     */
    byte[] load(String id) throws IOException;

    /**
     * Creates or replaces a world. Should be atomic: a failed save must not corrupt the previous data.
     *
     * @param id   world id
     * @param data serialized world
     */
    void save(String id, byte[] data) throws IOException;

    /**
     * @param id world id
     * @return whether the world exists
     */
    boolean exists(String id) throws IOException;

    /**
     * Deletes a world, does nothing if it does not exist.
     *
     * @param id world id
     */
    void delete(String id) throws IOException;

    /**
     * Releases resources (connections...). Called once by {@link fr.geeklegend.slimeworld.SlimeWorldManager#shutdown()}.
     */
    default void close() {}
}
