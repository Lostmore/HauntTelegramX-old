package haunt.trust.game;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import haunt.trust.database.SQL;
import haunt.trust.utils.MessageService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

public class ActionLog {
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter DB_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DISPLAY_TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private static final int SERVER_TO_MSK_HOURS = 1;

    public static List<ActionLogEntry> search(ActionQuery query) throws SQLException {
        QueryParts parts = buildWhere(query);
        String sql = "SELECT * FROM ACTION_LOGS" + parts.where + " ORDER BY timestamp DESC LIMIT ? OFFSET ?";

        Connection conn = getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            int index = bindWhere(stmt, parts, 1);
            stmt.setInt(index++, Math.max(1, query.limit));
            stmt.setInt(index, Math.max(0, query.offset));
            return readEntries(stmt);
        }
    }

    public static int count(ActionQuery query) throws SQLException {
        QueryParts parts = buildWhere(query);
        String sql = "SELECT COUNT(*) FROM ACTION_LOGS" + parts.where;

        Connection conn = getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            bindWhere(stmt, parts, 1);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public static String getRawJson(long id) throws SQLException {
        String query = "SELECT data FROM ACTION_LOGS WHERE id = ?";
        Connection conn = getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString("data") : null;
            }
        }
    }

    public static List<ActionLogEntry> searchByPlayerName(String playerName) throws SQLException {
        ActionQuery query = new ActionQuery();
        query.playerName = playerName;
        query.limit = 50;
        return search(query);
    }

    public static List<ActionLogEntry> searchByActionType(String actionType, int limit) throws SQLException {
        ActionQuery query = new ActionQuery();
        query.limit = limit;
        if (actionType != null && !actionType.equals("%") && !actionType.equalsIgnoreCase("all")) {
            query.actionTypes.add(actionType);
        }
        return search(query);
    }

    public static List<ActionLogEntry> searchRecentAuctions(int limit) throws SQLException {
        return searchByActionType("auction", limit);
    }

    public static List<ActionLogEntry> searchByDateRange(Date startDate, Date endDate) throws SQLException {
        ActionQuery query = new ActionQuery();
        query.startDate = startDate;
        query.endDate = endDate;
        query.limit = 100;
        return search(query);
    }

    public static ActionStats getStats(int days) throws SQLException {
        String query = """
                SELECT action_type, COUNT(*) count, COALESCE(SUM(amount), 0) total
                FROM ACTION_LOGS
                WHERE timestamp > DATE_SUB(NOW(), INTERVAL ? DAY)
                GROUP BY action_type
                """;

        ActionStats stats = new ActionStats();
        Connection conn = getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setInt(1, days);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String type = rs.getString("action_type");
                    int count = rs.getInt("count");
                    double total = rs.getDouble("total");
                    stats.totalActions += count;

                    if ("auction".equals(type)) {
                        stats.auctionCount = count;
                        stats.auctionVolume = total;
                    } else if ("player_pay".equals(type)) {
                        stats.payCount = count;
                        stats.payVolume = total;
                    } else if ("pickup".equals(type)) {
                        stats.pickupCount = count;
                    } else if ("transfer".equals(type)) {
                        stats.transferCount = count;
                    } else if ("town_join".equals(type)) {
                        stats.townJoinCount = count;
                    } else if ("town_leave".equals(type)) {
                        stats.townLeaveCount = count;
                    }
                }
            }
        }
        return stats;
    }

    private static QueryParts buildWhere(ActionQuery query) {
        QueryParts parts = new QueryParts();

        if (query.id != null) {
            parts.whereParts.add("id = ?");
            parts.values.add(query.id);
        }

        if (query.playerName != null && !query.playerName.isBlank()) {
            parts.whereParts.add("(actor_name = ? OR target_name = ? OR data LIKE ?)");
            parts.values.add(query.playerName);
            parts.values.add(query.playerName);
            parts.values.add("%" + query.playerName + "%");
        }

        if (!query.actionTypes.isEmpty()) {
            StringJoiner placeholders = new StringJoiner(",", "action_type IN (", ")");
            for (String type : query.actionTypes) {
                placeholders.add("?");
                parts.values.add(type);
            }
            parts.whereParts.add(placeholders.toString());
        }

        if (query.startDate != null && query.endDate != null) {
            parts.whereParts.add("timestamp BETWEEN ? AND ?");
            parts.values.add(new Timestamp(query.startDate.getTime()));
            parts.values.add(new Timestamp(query.endDate.getTime()));
        } else if (query.days > 0) {
            parts.whereParts.add("timestamp > DATE_SUB(NOW(), INTERVAL ? DAY)");
            parts.values.add(query.days);
        }

        if (!parts.whereParts.isEmpty()) {
            parts.where = " WHERE " + String.join(" AND ", parts.whereParts);
        }
        return parts;
    }

    private static int bindWhere(PreparedStatement stmt, QueryParts parts, int startIndex) throws SQLException {
        int index = startIndex;
        for (Object value : parts.values) {
            if (value instanceof Long longValue) {
                stmt.setLong(index++, longValue);
            } else if (value instanceof Integer intValue) {
                stmt.setInt(index++, intValue);
            } else if (value instanceof Timestamp timestamp) {
                stmt.setTimestamp(index++, timestamp);
            } else {
                stmt.setString(index++, String.valueOf(value));
            }
        }
        return index;
    }

    private static List<ActionLogEntry> readEntries(PreparedStatement stmt) throws SQLException {
        List<ActionLogEntry> results = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                results.add(ActionLogEntry.fromResultSet(rs));
            }
        }
        return results;
    }

    private static Connection getConnection() throws SQLException {
        try {
            return SQL.getConnection();
        } catch (ClassNotFoundException e) {
            throw new SQLException("Could not load SQL", e);
        }
    }

    private static String stringOrNull(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static Double doubleOrNull(ResultSet rs, String column) {
        try {
            double value = rs.getDouble(column);
            return rs.wasNull() ? null : value;
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static Integer intOrNull(ResultSet rs, String column) {
        try {
            int value = rs.getInt(column);
            return rs.wasNull() ? null : value;
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static String getString(JsonObject json, String key) {
        if (json == null) {
            return null;
        }
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element.getAsString();
    }

    private static Double getDouble(JsonObject json, String key) {
        if (json == null) {
            return null;
        }
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element.getAsDouble();
    }

    private static Integer getInt(JsonObject json, String key) {
        if (json == null) {
            return null;
        }
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element.getAsInt();
    }

    private static String cleanText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value
                .replaceAll("[§\\u00a7][0-9a-fk-orA-FK-OR]", "")
                .replaceAll("[¨\\u00a8][0-9a-fA-F]{6}", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String html(String value) {
        return MessageService.escapeHtml(value);
    }

    public static class ActionStats {
        public int totalActions;
        public int auctionCount;
        public double auctionVolume;
        public int payCount;
        public double payVolume;
        public int pickupCount;
        public int transferCount;
        public int townJoinCount;
        public int townLeaveCount;
    }

    public static class ActionQuery {
        public Long id;
        public String playerName;
        public List<String> actionTypes = new ArrayList<>();
        public Date startDate;
        public Date endDate;
        public int days;
        public int limit = 8;
        public int offset;

        public ActionQuery copy() {
            ActionQuery copy = new ActionQuery();
            copy.id = id;
            copy.playerName = playerName;
            copy.actionTypes = new ArrayList<>(actionTypes);
            copy.startDate = startDate == null ? null : new Date(startDate.getTime());
            copy.endDate = endDate == null ? null : new Date(endDate.getTime());
            copy.days = days;
            copy.limit = limit;
            copy.offset = offset;
            return copy;
        }
    }

    private static class QueryParts {
        String where = "";
        List<String> whereParts = new ArrayList<>();
        List<Object> values = new ArrayList<>();
    }

    public static class ActionLogEntry {
        public long id;
        public String server;
        public Timestamp timestamp;
        public String timestampRaw;
        public String actionType;
        public String jsonData;
        public String action;
        public String reason;
        public String actorName;
        public String targetName;
        public String itemName;
        public String itemType;
        public String itemCustomId;
        public int itemAmount;
        public String civName;
        public double amount;

        static ActionLogEntry fromResultSet(ResultSet rs) throws SQLException {
            ActionLogEntry entry = new ActionLogEntry();
            entry.id = rs.getLong("id");
            entry.server = rs.getString("server");
            entry.timestamp = rs.getTimestamp("timestamp");
            entry.timestampRaw = rs.getString("timestamp");
            entry.actionType = rs.getString("action_type");
            entry.jsonData = rs.getString("data");
            entry.actorName = stringOrNull(rs, "actor_name");
            entry.targetName = stringOrNull(rs, "target_name");
            entry.itemName = cleanText(stringOrNull(rs, "item_name"));
            entry.itemType = stringOrNull(rs, "item_type");
            entry.itemCustomId = stringOrNull(rs, "item_custom_id");
            Integer dbItemAmount = intOrNull(rs, "item_amount");
            entry.itemAmount = dbItemAmount == null ? 0 : dbItemAmount;
            Double dbAmount = doubleOrNull(rs, "amount");
            entry.amount = dbAmount == null ? 0.0D : dbAmount;
            entry.parseJsonFallbacks();
            return entry;
        }

        private void parseJsonFallbacks() {
            try {
                JsonObject json = GSON.fromJson(jsonData, JsonObject.class);
                if (json == null) {
                    return;
                }

                action = fallback(getString(json, "action"), actionType);
                reason = getString(json, "reason");

                if ("PLAYER_PAY".equalsIgnoreCase(action)) {
                    JsonObject from = json.getAsJsonObject("from");
                    JsonObject to = json.getAsJsonObject("to");
                    actorName = fallback(actorName, getString(from, "name"));
                    targetName = fallback(targetName, getString(to, "name"));
                    Double jsonAmount = getDouble(json, "amount");
                    amount = amount == 0.0D && jsonAmount != null ? jsonAmount : amount;
                    return;
                }

                if ("AUCTION".equalsIgnoreCase(action)) {
                    JsonObject seller = json.getAsJsonObject("seller");
                    JsonObject buyer = json.getAsJsonObject("buyer");
                    actorName = fallback(actorName, getString(seller, "name"));
                    targetName = fallback(targetName, getString(buyer, "name"));
                    Double price = getDouble(json, "price");
                    amount = amount == 0.0D && price != null ? price : amount;
                    readItem(json.getAsJsonObject("item"));
                    return;
                }

                if ("ITEM_PICKUP".equalsIgnoreCase(action)) {
                    JsonObject player = json.getAsJsonObject("player");
                    actorName = fallback(actorName, getString(player, "name"));
                    readItem(json.getAsJsonObject("item"));
                    return;
                }

                if ("ITEM_TRANSFER".equalsIgnoreCase(action)) {
                    JsonObject from = json.getAsJsonObject("from");
                    JsonObject to = json.getAsJsonObject("to");
                    actorName = fallback(actorName, getString(from, "name"));
                    targetName = fallback(targetName, getString(to, "name"));
                    readItem(json.getAsJsonObject("item"));
                    return;
                }

                if ("TOWN_JOIN".equalsIgnoreCase(action) || "TOWN_LEAVE".equalsIgnoreCase(action)) {
                    JsonObject player = json.getAsJsonObject("player");
                    JsonObject town = json.getAsJsonObject("town");
                    JsonObject civ = json.getAsJsonObject("civ");
                    actorName = fallback(actorName, getString(player, "name"));
                    targetName = fallback(targetName, getString(town, "name"));
                    civName = fallback(civName, getString(civ, "name"));
                }
            } catch (Exception ignored) {
            }
        }

        private void readItem(JsonObject item) {
            if (item == null) {
                return;
            }

            itemType = fallback(itemType, getString(item, "type"));
            itemCustomId = fallback(itemCustomId, getString(item, "custom_id"));
            Integer amount = getInt(item, "amount");
            if (itemAmount <= 0 && amount != null) {
                itemAmount = amount;
            }

            if (itemName == null && item.has("meta") && item.get("meta").isJsonObject()) {
                itemName = cleanText(getString(item.getAsJsonObject("meta"), "display_name"));
            }

            itemName = fallback(itemName, itemCustomId);
            itemName = fallback(itemName, itemType);
        }

        private String itemWithAmount() {
            String name = fallback(itemName, "предмет неизвестен");
            if (itemAmount > 0) {
                return name + " x" + itemAmount;
            }
            return name;
        }

        public String getFormattedTime() {
            try {
                String raw = timestampRaw == null ? null : timestampRaw.split("\\.")[0];
                if (raw != null && !raw.isBlank()) {
                    return LocalDateTime.parse(raw, DB_TIME_FORMAT)
                            .plusHours(SERVER_TO_MSK_HOURS)
                            .format(DISPLAY_TIME_FORMAT);
                }
            } catch (Exception ignored) {
            }
            return DISPLAY_TIME_FORMAT.format(timestamp.toLocalDateTime().plusHours(SERVER_TO_MSK_HOURS));
        }

        public String toTelegramString() {
            String type = fallback(action, actionType);
            String idPart = "<code>#" + id + "</code>";
            String time = html(getFormattedTime());

            if ("PLAYER_PAY".equalsIgnoreCase(type)) {
                return String.format(Locale.US, "%s <b>%s</b> | %s -> %s | <b>%,.0f</b> монет",
                        idPart, time, html(fallback(actorName, "неизвестно")),
                        html(fallback(targetName, "неизвестно")), amount);
            }

            if ("AUCTION".equalsIgnoreCase(type)) {
                return String.format(Locale.US, "%s <b>%s</b> | аукцион | %s -> %s | %s | <b>%,.0f</b>",
                        idPart, time, html(fallback(actorName, "неизвестно")),
                        html(fallback(targetName, "неизвестно")),
                        html(itemWithAmount()), amount);
            }

            if ("ITEM_PICKUP".equalsIgnoreCase(type)) {
                return String.format("%s <b>%s</b> | подобрал | %s | %s",
                        idPart, time, html(fallback(actorName, "неизвестно")),
                        html(itemWithAmount()));
            }

            if ("ITEM_TRANSFER".equalsIgnoreCase(type)) {
                if ("chest_deposit".equalsIgnoreCase(reason)) {
                    return String.format("%s <b>%s</b> | в сундук | %s -> %s | %s",
                            idPart, time, html(fallback(actorName, "неизвестно")),
                            html(fallback(targetName, "сундук")),
                            html(itemWithAmount()));
                }
                if ("chest_withdraw".equalsIgnoreCase(reason)) {
                    return String.format("%s <b>%s</b> | из сундука | %s -> %s | %s",
                            idPart, time, html(fallback(actorName, "сундук")),
                            html(fallback(targetName, "неизвестно")),
                            html(itemWithAmount()));
                }
                return String.format("%s <b>%s</b> | передача | %s -> %s | %s",
                        idPart, time, html(fallback(actorName, "неизвестно")),
                        html(fallback(targetName, "неизвестно")),
                        html(itemWithAmount()));
            }

            if ("TOWN_JOIN".equalsIgnoreCase(type)) {
                return String.format("%s <b>%s</b> | вступил | %s -> город <b>%s</b>, цивилизация <b>%s</b>",
                        idPart, time, html(fallback(actorName, "неизвестно")),
                        html(fallback(targetName, "неизвестно")),
                        html(fallback(civName, "неизвестно")));
            }

            if ("TOWN_LEAVE".equalsIgnoreCase(type)) {
                return String.format("%s <b>%s</b> | вышел | %s из города <b>%s</b>, цивилизация <b>%s</b>",
                        idPart, time, html(fallback(actorName, "неизвестно")),
                        html(fallback(targetName, "неизвестно")),
                        html(fallback(civName, "неизвестно")));
            }

            return String.format("%s <b>%s</b> | %s | %s -> %s",
                    idPart, time, html(type), html(fallback(actorName, "неизвестно")),
                    html(fallback(targetName, fallback(itemName, "детали в raw"))));
        }

        public String toShortString() {
            return toTelegramString().replaceAll("<[^>]+>", "");
        }

        public String toDetailedString() {
            return "Log #" + id
                    + "\nTime: " + getFormattedTime()
                    + "\nServer: " + server
                    + "\nType: " + actionType
                    + "\nAction: " + fallback(action, actionType)
                    + "\nActor: " + fallback(actorName, "unknown")
                    + "\nTarget: " + fallback(targetName, "none")
                    + "\nCiv: " + fallback(civName, "none")
                    + "\nItem: " + itemWithAmount()
                    + "\nAmount: " + String.format(Locale.US, "%,.0f", amount);
        }
    }
}
