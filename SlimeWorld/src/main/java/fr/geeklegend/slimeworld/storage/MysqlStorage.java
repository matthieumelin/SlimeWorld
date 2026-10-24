package fr.geeklegend.slimeworld.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.sql.*;

/**
 * Stores worlds in a MySQL / MariaDB table (created if missing) through a small HikariCP pool.
 * Useful to share worlds between several servers.
 */
public class MysqlStorage implements Storage {
    private final HikariDataSource dataSource;
    private final String table;

    /**
     * @param jdbcUrl  e.g. {@code jdbc:mysql://localhost:3306/minecraft}
     * @param user     database user
     * @param password database password
     * @param table    table name, {@code [A-Za-z0-9_]{1,64}}
     * @throws IOException if the database is unreachable or the table cannot be created
     */
    public MysqlStorage(String jdbcUrl, String user, String password, String table) throws IOException {
        if (!table.matches("[A-Za-z0-9_]{1,64}")) throw new IllegalArgumentException("Invalid table name: " + table);
        this.table = table;

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setMaximumPoolSize(4);
        config.setPoolName("SlimeWorld");
        this.dataSource = new HikariDataSource(config);

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS `" + table + "` (" + "id VARCHAR(48) NOT NULL PRIMARY KEY," + "data LONGBLOB NOT NULL," + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
        } catch (SQLException e) {
            dataSource.close();
            throw new IOException("Unable to create " + table + " table", e);
        }
    }

    @Override
    public byte[] load(String id) throws IOException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement preparedStatement = connection.prepareStatement("SELECT data FROM `" + table + "` WHERE id = ?")) {
            preparedStatement.setString(1, id);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                return resultSet.next() ? resultSet.getBytes(1) : null;
            }
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void save(String id, byte[] data) throws IOException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement preparedStatement = connection.prepareStatement("INSERT INTO `" + table + "` (id, data) VALUES (?, ?) ON DUPLICATE KEY UPDATE data = VALUES(data)")) {
            preparedStatement.setString(1, id);
            preparedStatement.setBytes(2, data);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    @Override
    public boolean exists(String id) throws IOException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement preparedStatement = connection.prepareStatement("SELECT 1 FROM `" + table + "` WHERE id = ?")) {
            preparedStatement.setString(1, id);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void delete(String id) throws IOException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement preparedStatement = connection.prepareStatement("DELETE FROM `" + table + "` WHERE id = ?")) {
            preparedStatement.setString(1, id);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
