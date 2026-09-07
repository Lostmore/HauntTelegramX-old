package haunt.trust.database;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import haunt.trust.config.BotConfig;
import haunt.trust.config.ConfigLoader;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;

public class SQL {
    public static final String GLOBAL_AUTHORIZED_USERS_TABLE = "GLOBAL_AUTHORIZED_USERS";
    private static final Gson GSON = new Gson();

    public static String global_dsn = "";
    public static String global_hostname = "";
    public static String global_port = "";
    public static String global_username = "";
    public static String global_password = "";
    public static String global_db = "";
    public static Connection connection;

    public static void initialize() throws SQLException, ClassNotFoundException {
        System.out.println("Initializing SQL");
        System.out.println("Initializing Global SQL Database");

        BotConfig config = ConfigLoader.getInstance();
        global_hostname = config.getGlobalHostname();
        global_port = String.valueOf(config.getGlobalPort());
        global_username = config.getGlobalUsername();
        global_password = config.getGlobalPassword();
        global_db = config.getGlobalDatabase();

        global_dsn = "jdbc:mysql://" + global_hostname + ":" + global_port + "/" + global_db + "?useSSL=false&serverTimezone=UTC";

        if (connection != null && !connection.isClosed()) {
            connection.close();
        }

        connection = DriverManager.getConnection(global_dsn, global_username, global_password);
        ensureGlobalSchema(connection);

        System.out.println("\t Connected to GLOBAL database at: " + global_hostname + ":" + global_port);
        System.out.println("Initializing SQL Finished");
    }

    private static void ensureGlobalSchema(Connection conn) throws SQLException {
        String createAuthorizedUsersTable = "CREATE TABLE IF NOT EXISTS `" + GLOBAL_AUTHORIZED_USERS_TABLE + "` (" +
                "`telegram_id` BIGINT NOT NULL," +
                "`nickname` VARCHAR(32) NULL," +
                "`linked_at` BIGINT NOT NULL," +
                "`ticket_history` LONGTEXT NULL," +
                "`notify_border` BOOLEAN DEFAULT TRUE," +
                "`notify_struct` BOOLEAN DEFAULT TRUE," +
                "`notify_gov` BOOLEAN DEFAULT TRUE," +
                "PRIMARY KEY (`telegram_id`)," +
                "KEY `idx_gauth_nickname` (`nickname`)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

        try (Statement statement = conn.createStatement()) {
            statement.execute(createAuthorizedUsersTable);
            ensureColumnNullable(conn, GLOBAL_AUTHORIZED_USERS_TABLE, "nickname");
            ensureColumnExists(conn, GLOBAL_AUTHORIZED_USERS_TABLE, "ticket_history", "LONGTEXT NULL");
            ensureColumnExists(conn, GLOBAL_AUTHORIZED_USERS_TABLE, "notify_border", "BOOLEAN DEFAULT TRUE");
            ensureColumnExists(conn, GLOBAL_AUTHORIZED_USERS_TABLE, "notify_struct", "BOOLEAN DEFAULT TRUE");
            ensureColumnExists(conn, GLOBAL_AUTHORIZED_USERS_TABLE, "notify_gov", "BOOLEAN DEFAULT TRUE");
            
        }
    }

    private static void ensureColumnExists(Connection conn, String tableName, String columnName, String definition) {
        try (Statement statement = conn.createStatement()) {
            statement.execute("ALTER TABLE `" + tableName + "` ADD COLUMN `" + columnName + "` " + definition);
        } catch (SQLException ignored) {
            // Игнорируем ошибку "Duplicate column name", если колонка уже существует
        }
    }

    private static void ensureColumnNullable(Connection conn, String tableName, String columnName) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.execute("ALTER TABLE `" + tableName + "` MODIFY COLUMN `" + columnName + "` VARCHAR(32) NULL");
        }
    }

    public static Connection getConnection() throws SQLException, ClassNotFoundException {
        if (connection == null || connection.isClosed()) {
            initialize();
        }
        return connection;
    }

    public static boolean hasTable(String tableName) throws SQLException {
        try (Connection conn = getConnection();
             ResultSet rs = conn.getMetaData().getTables(null, null, tableName, new String[]{"TABLE"})) {
            return rs.next();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static void close(ResultSet rs, PreparedStatement ps, Connection conn) {
        try {
            if (rs != null) rs.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        try {
            if (ps != null) ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        try {
            if (conn != null && !conn.isClosed()) conn.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static void upsertAuthorizedUser(long telegramId, String nickname) {
        String sql = "INSERT INTO `" + GLOBAL_AUTHORIZED_USERS_TABLE + "` " +
                "(`telegram_id`,`nickname`,`linked_at`) " +
                "VALUES (?,?,?) " +
                "ON DUPLICATE KEY UPDATE " +
                "`nickname`=VALUES(`nickname`), " +
                "`linked_at`=VALUES(`linked_at`)";

        long now = System.currentTimeMillis();

        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setLong(1, telegramId);
            ps.setString(2, nickname);
            ps.setLong(3, now);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("Failed to upsert authorized user: " + e.getMessage());
        }
    }

    public static boolean isSettingEnabled(long telegramId, String settingColumn) {
        String sql = "SELECT `" + settingColumn + "` FROM `" + GLOBAL_AUTHORIZED_USERS_TABLE + "` WHERE `telegram_id` = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setLong(1, telegramId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean(settingColumn);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to get setting " + settingColumn + ": " + e.getMessage());
        }
        return true; // default true
    }

    public static void toggleSetting(long telegramId, String settingColumn) {
        String sql = "UPDATE `" + GLOBAL_AUTHORIZED_USERS_TABLE + "` SET `" + settingColumn + "` = NOT `" + settingColumn + "` WHERE `telegram_id` = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setLong(1, telegramId);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("Failed to toggle setting " + settingColumn + ": " + e.getMessage());
        }
    }

    public static void markAuthorizedUserUnlinked(long telegramId) {
        String sql = "UPDATE `" + GLOBAL_AUTHORIZED_USERS_TABLE + "` SET `nickname`=NULL, `linked_at`=0 WHERE `telegram_id`=?";

        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setLong(1, telegramId);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("Failed to mark authorized user unlinked: " + e.getMessage());
        }
    }

    public static int clearCivCraftUserBindings() {
        String sql = "UPDATE `" + GLOBAL_AUTHORIZED_USERS_TABLE + "` " +
                "SET `nickname`=NULL, `linked_at`=0 " +
                "WHERE `nickname` IS NOT NULL AND `nickname` <> ''";

        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            return ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("Failed to clear CivCraft user bindings: " + e.getMessage());
            return -1;
        }
    }

    public static void ensureAuthorizedUser(long telegramId) {
        String sql = "INSERT INTO `" + GLOBAL_AUTHORIZED_USERS_TABLE + "` " +
                "(`telegram_id`,`nickname`,`linked_at`,`ticket_history`) " +
                "VALUES (?,NULL,0,?) " +
                "ON DUPLICATE KEY UPDATE `telegram_id`=`telegram_id`";

        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setLong(1, telegramId);
            ps.setString(2, "[]");
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("Failed to ensure authorized user row: " + e.getMessage());
        }
    }

    public static void appendTicketHistory(long telegramId, JsonObject entry) {
        ensureAuthorizedUser(telegramId);

        String selectSql = "SELECT `ticket_history` FROM `" + GLOBAL_AUTHORIZED_USERS_TABLE + "` WHERE `telegram_id`=?";
        String updateSql = "UPDATE `" + GLOBAL_AUTHORIZED_USERS_TABLE + "` SET `ticket_history`=? WHERE `telegram_id`=?";

        try {
            JsonArray history = new JsonArray();

            try (PreparedStatement ps = getConnection().prepareStatement(selectSql)) {
                ps.setLong(1, telegramId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String rawHistory = rs.getString("ticket_history");
                        if (rawHistory != null && !rawHistory.isBlank()) {
                            history = parseTicketHistory(rawHistory);
                        }
                    }
                }
            }

            history.add(entry);

            try (PreparedStatement ps = getConnection().prepareStatement(updateSql)) {
                ps.setString(1, GSON.toJson(history));
                ps.setLong(2, telegramId);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            System.err.println("Failed to append ticket history: " + e.getMessage());
        }
    }

    public static JsonArray getTicketHistory(long telegramId) {
        String sql = "SELECT `ticket_history` FROM `" + GLOBAL_AUTHORIZED_USERS_TABLE + "` WHERE `telegram_id`=?";

        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setLong(1, telegramId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new JsonArray();
                }

                String rawHistory = rs.getString("ticket_history");
                if (rawHistory == null || rawHistory.isBlank()) {
                    return new JsonArray();
                }

                return parseTicketHistory(rawHistory);
            }
        } catch (Exception e) {
            System.err.println("Failed to get ticket history: " + e.getMessage());
        }

        return new JsonArray();
    }

    public static Map<Long, JsonArray> getAllTicketHistories() {
        String sql = "SELECT `telegram_id`, `ticket_history` FROM `" + GLOBAL_AUTHORIZED_USERS_TABLE + "` " +
                "WHERE `ticket_history` IS NOT NULL AND `ticket_history` <> ''";
        Map<Long, JsonArray> histories = new HashMap<>();

        try (PreparedStatement ps = getConnection().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long telegramId = rs.getLong("telegram_id");
                String rawHistory = rs.getString("ticket_history");
                if (rawHistory == null || rawHistory.isBlank()) {
                    continue;
                }

                try {
                    JsonArray history = parseTicketHistory(rawHistory);
                    if (history.size() > 0) {
                        histories.put(telegramId, history);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse ticket history for " + telegramId + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to get all ticket histories: " + e.getMessage());
        }

        return histories;
    }

    private static JsonArray parseTicketHistory(String rawHistory) {
        JsonArray history = new JsonArray();
        JsonElement parsed = new JsonParser().parse(rawHistory);

        if (parsed.isJsonPrimitive() && parsed.getAsJsonPrimitive().isString()) {
            parsed = new JsonParser().parse(parsed.getAsString());
        }

        collectTicketHistoryObjects(parsed, history);
        return history;
    }

    private static void collectTicketHistoryObjects(JsonElement element, JsonArray history) {
        if (element == null || element.isJsonNull()) {
            return;
        }

        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectTicketHistoryObjects(child, history);
            }
            return;
        }

        if (!element.isJsonObject()) {
            return;
        }

        JsonObject object = element.getAsJsonObject();
        if (isTicketHistoryObject(object)) {
            history.add(object);
            return;
        }

        for (Map.Entry<String, JsonElement> child : object.entrySet()) {
            collectTicketHistoryObjects(child.getValue(), history);
        }
    }

    private static boolean isTicketHistoryObject(JsonObject object) {
        if (hasAnyKey(object, "ticketId", "ticket_id", "event", "issueDescription", "description", "evidence")) {
            return true;
        }

        return object.has("id") && hasAnyKey(object, "username", "playerName", "nickname", "message", "text");
    }

    private static boolean hasAnyKey(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key)) {
                return true;
            }
        }
        return false;
    }
}
