package dev.chatguard.database;

import dev.chatguard.detection.ViolationType;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Asynchronous SQLite database manager for persisting chat violation records.
 * <p>
 * All write operations are non-blocking — they are submitted to a single-thread executor
 * and never block the calling (server) thread. Read operations return {@link CompletableFuture}
 * instances that resolve on the executor thread.
 * <p>
 * Uses WAL journal mode and NORMAL synchronous for optimal write throughput.
 */
public class DatabaseManager {

    private final ExecutorService executor;
    private final AtomicInteger pendingCount = new AtomicInteger(0);
    private final int maxQueueSize;
    private Connection connection;
    private final File dbFile;
    private final Logger logger;

    /**
     * Constructs a new DatabaseManager.
     *
     * @param dbFile       the SQLite database file
     * @param maxQueueSize the maximum number of pending async write operations
     * @param logger       the logger for error/debug output
     */
    public DatabaseManager(File dbFile, int maxQueueSize, Logger logger) {
        this.dbFile = dbFile;
        this.maxQueueSize = maxQueueSize;
        this.logger = logger;

        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "ChatGuard-DB");
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newSingleThreadExecutor(factory);
    }

    /**
     * Initializes the database connection and creates the schema.
     * <p>
     * Opens the SQLite connection, enables WAL journal mode and NORMAL synchronous pragma,
     * and creates the violations table with indices if they do not already exist.
     *
     * @return a {@link CompletableFuture} that completes when initialization is finished
     */
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try {
                // Ensure parent directory exists
                File parentDir = dbFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }

                connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("PRAGMA journal_mode=WAL");
                    stmt.execute("PRAGMA synchronous=NORMAL");

                    stmt.execute("""
                            CREATE TABLE IF NOT EXISTS violations (
                                id INTEGER PRIMARY KEY AUTOINCREMENT,
                                player_id TEXT NOT NULL,
                                player_name TEXT NOT NULL,
                                type TEXT NOT NULL,
                                score REAL NOT NULL,
                                original_message TEXT NOT NULL,
                                details TEXT,
                                timestamp INTEGER NOT NULL
                            )
                            """);

                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_violations_player ON violations(player_id)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_violations_timestamp ON violations(timestamp)");
                }

                logger.info("ChatGuard database initialized successfully: " + dbFile.getName());
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to initialize ChatGuard database", e);
                throw new CompletionException(e);
            }
        }, executor);
    }

    /**
     * Logs a violation record to the database asynchronously.
     * <p>
     * If the pending write queue has reached {@code maxQueueSize}, the write is silently
     * dropped to prevent unbounded memory growth.
     *
     * @param playerId        the UUID of the offending player
     * @param playerName      the display name of the offending player
     * @param type            the type of violation detected
     * @param score           the confidence score of the detection
     * @param originalMessage the original chat message
     * @param details         additional detection details, may be {@code null}
     */
    public void logViolation(UUID playerId, String playerName, ViolationType type,
                             double score, String originalMessage, String details) {
        if (pendingCount.get() >= maxQueueSize) {
            logger.warning("ChatGuard DB queue full (" + maxQueueSize + "), dropping violation log for " + playerName);
            return;
        }

        pendingCount.incrementAndGet();
        executor.submit(() -> {
            try {
                if (connection == null || connection.isClosed()) {
                    logger.warning("Database connection is not available, skipping violation log");
                    return;
                }

                String sql = "INSERT INTO violations (player_id, player_name, type, score, original_message, details, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, playerId.toString());
                    ps.setString(2, playerName);
                    ps.setString(3, type.name());
                    ps.setDouble(4, score);
                    ps.setString(5, originalMessage);
                    ps.setString(6, details);
                    ps.setLong(7, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to log violation for " + playerName, e);
            } finally {
                pendingCount.decrementAndGet();
            }
        });
    }

    /**
     * Retrieves the most recent violations for a specific player.
     *
     * @param playerId the UUID of the player
     * @param limit    the maximum number of records to return
     * @return a future containing the list of {@link ViolationRecord}s, ordered newest first
     */
    public CompletableFuture<List<ViolationRecord>> getViolations(UUID playerId, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<ViolationRecord> records = new ArrayList<>();
            try {
                if (connection == null || connection.isClosed()) {
                    return records;
                }

                String sql = "SELECT id, player_id, player_name, type, score, original_message, details, timestamp FROM violations WHERE player_id = ? ORDER BY timestamp DESC LIMIT ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, playerId.toString());
                    ps.setInt(2, limit);

                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            records.add(mapRow(rs));
                        }
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to query violations for " + playerId, e);
            }
            return records;
        }, executor);
    }

    /**
     * Returns the total number of violation records in the database.
     *
     * @return a future containing the total violation count
     */
    public CompletableFuture<Integer> getViolationCount() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (connection == null || connection.isClosed()) {
                    return 0;
                }

                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM violations")) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to count violations", e);
            }
            return 0;
        }, executor);
    }

    /**
     * Deletes all violation records for a specific player.
     *
     * @param playerId the UUID of the player whose records should be cleared
     * @return a future that completes when the deletion is finished
     */
    public CompletableFuture<Void> clearViolations(UUID playerId) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (connection == null || connection.isClosed()) {
                    return;
                }

                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM violations WHERE player_id = ?")) {
                    ps.setString(1, playerId.toString());
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to clear violations for " + playerId, e);
            }
        }, executor);
    }

    /**
     * Gracefully shuts down the database manager.
     * <p>
     * Stops accepting new tasks, waits up to 5 seconds for pending operations to complete,
     * and then closes the underlying SQLite connection.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                logger.warning("ChatGuard DB executor did not terminate within 5 seconds, forcing shutdown");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to close database connection", e);
        }

        logger.info("ChatGuard database shut down");
    }

    /**
     * Returns the number of pending asynchronous write operations.
     *
     * @return the current pending write count
     */
    public int getPendingCount() {
        return pendingCount.get();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private ViolationRecord mapRow(ResultSet rs) throws SQLException {
        return new ViolationRecord(
                rs.getLong("id"),
                rs.getString("player_id"),
                rs.getString("player_name"),
                ViolationType.valueOf(rs.getString("type")),
                rs.getDouble("score"),
                rs.getString("original_message"),
                rs.getString("details"),
                rs.getLong("timestamp")
        );
    }
}
