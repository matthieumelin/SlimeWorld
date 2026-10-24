package fr.geeklegend.slimeworld.storage;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Stores each world as a {@code <id>.isl} file in a directory. Writes go through a temporary file
 * and an atomic move, so a crash during a save keeps the previous version.
 */
public class FileStorage implements Storage {
    private final Path directory;

    /**
     * @param directory directory of the world files, created if missing
     */
    public FileStorage(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
    }

    private Path file(String id) {
        return directory.resolve(id + ".isl");
    }

    @Override
    public byte[] load(String id) throws IOException {
        Path file = file(id);
        return Files.exists(file) ? Files.readAllBytes(file) : null;
    }

    @Override
    public void save(String id, byte[] data) throws IOException {
        Path target = file(id);
        Path temp = directory.resolve(id + ".isl.tmp");
        Files.write(temp, data);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public boolean exists(String id) throws IOException {
        return Files.exists(file(id));
    }

    @Override
    public void delete(String id) throws IOException {
        Files.deleteIfExists(file(id));
    }
}
