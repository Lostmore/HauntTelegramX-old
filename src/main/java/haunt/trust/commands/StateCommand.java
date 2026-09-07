package haunt.trust.commands;

import haunt.trust.Bot;
import haunt.trust.database.SQL;
import haunt.trust.utils.MessageService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.*;

public class StateCommand {
    private final MessageService messageService;

    private static final List<String> VALID_SERVERS = Arrays.asList("taurus");
    private static final List<String> VALID_MONTHS = Arrays.asList(
            "январь", "февраль", "март", "апрель", "май", "июнь",
            "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"
    );

    public StateCommand(MessageService messageService) {
        this.messageService = messageService;
    }

    public void handleStateCommand(String text, Long chatId, Integer threadId) {
        if (!isValidChatAndTopic(chatId, threadId)) {
            messageService.sendMessageHtml(chatId, threadId, "❌ Команда /state доступна только в чате <a href=\"https://t.me/c/2521750905/8\">'Детектор Кротов'</a>");
            return;
        }

        String[] parts = text.split(" ");
        if (parts.length == 1) {
            sendHelpMessage(chatId, threadId);
            return;
        }

        String serverOrAll = parts[1].toLowerCase();
        String month = (parts.length >= 3) ? parts[2].toLowerCase() : null;

        if (serverOrAll.equals("all")) {
            handleAllServers(chatId, threadId, month);
        } else if (VALID_SERVERS.contains(serverOrAll)) {
            handleSingleServer(chatId, threadId, serverOrAll, month);
        } else {
            messageService.sendMessage(chatId, threadId,
                    "❌ Упс.. что-то пошло не по плану. Проверь название `сервера`!");
        }
    }

    private boolean isValidChatAndTopic(Long chatId, Integer threadId) {
        return chatId.equals(-1002521750905L) && threadId != null && threadId == 8;
    }

    private void sendHelpMessage(Long chatId, Integer threadId) {
        String helpText = "🌳 Оп! А тут ещё и подкоманды нашлись!\n\n" +
                "• `/state` - сбор статистики о кротах серверов CivCraft;\n\n" +
                "• `/state all [Месяц]` - сбор статистики о кротах со всех серверов CivCraft. " +
                "Также можно узнать статистику за определенный месяц;\n\n" +
                "• `/state [Сервер] [Месяц]` - сбор статистики о кротах определенного сервера. " +
                "Также можно узнать статистику за определенный месяц.";

        messageService.sendMessage(chatId, threadId, helpText);
    }

    private void handleAllServers(Long chatId, Integer threadId, String month) {
        if (month == null) {
            sendCurrentMonthAllServersStats(chatId, threadId);
        } else if (!VALID_MONTHS.contains(month)) {
            messageService.sendMessage(chatId, threadId,
                    "❌ Упс.. что-то пошло не по плану. Проверь название `месяца`!");
        } else {
            sendSpecificMonthAllServersStats(chatId, threadId, month);
        }
    }

    private void handleSingleServer(Long chatId, Integer threadId, String server, String month) {
        if (month == null) {
            sendCurrentMonthServerStats(chatId, threadId, server);
        } else if (!VALID_MONTHS.contains(month)) {
            messageService.sendMessage(chatId, threadId,
                    "❌ Упс.. что-то пошло не по плану. Проверь название `месяца`!");
        } else {
            sendSpecificMonthServerStats(chatId, threadId, server, month);
        }
    }

    private void sendCurrentMonthAllServersStats(Long chatId, Integer threadId) {
        Calendar calendar = Calendar.getInstance();
        int currentMonth = calendar.get(Calendar.MONTH);
        int currentYear = calendar.get(Calendar.YEAR);

        String[] monthNames = {"январь", "февраль", "март", "апрель", "май", "июнь",
                "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"};
        String month = monthNames[currentMonth];

        Map<String, String[]> months = getMonthDateRanges();
        String[] range = months.get(month.toLowerCase());

        if (range == null) {
            messageService.sendMessage(chatId, threadId, "❌ Не удалось получить диапазон дат для месяца: " + month);
            return;
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy");
        try {
            Date startDate = dateFormat.parse(range[0]);
            Date endDate = dateFormat.parse(range[1]);

            long startMillis = startDate.getTime();
            long endMillis = endDate.getTime();

            Connection conn = SQL.getConnection();
            String query = "SELECT * FROM GLOBAL_SESSIONS WHERE time BETWEEN ? AND ?";

            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setLong(1, startMillis);
                ps.setLong(2, endMillis);

                ResultSet rs = ps.executeQuery();

                Map<String, HashMap<String, Integer>> serverOreStats = new HashMap<>();
                Map<String, HashMap<String, Integer>> serverPlayerCounts = new HashMap<>();
                Map<String, HashSet<String>> serverUniquePlayers = new HashMap<>();

                while (rs.next()) {
                    String key = rs.getString("key");
                    String value = rs.getString("value");

                    if (!key.contains(":")) continue;
                    String[] keyParts = key.split(":", 2);
                    String serverInDb = keyParts[0];
                    String player = keyParts[1];

                    serverOreStats.putIfAbsent(serverInDb, new HashMap<>());
                    serverPlayerCounts.putIfAbsent(serverInDb, new HashMap<>());
                    serverUniquePlayers.putIfAbsent(serverInDb, new HashSet<>());

                    serverUniquePlayers.get(serverInDb).add(player);
                    serverPlayerCounts.get(serverInDb).put(player, serverPlayerCounts.get(serverInDb).getOrDefault(player, 0) + 1);

                    if (!value.contains(":")) continue;
                    String[] valParts = value.split(":", 2);
                    String oreType = valParts[0];
                    int amount = Integer.parseInt(valParts[1]);

                    if (oreType.endsWith("_ORE")) {
                        serverOreStats.get(serverInDb).put(oreType, serverOreStats.get(serverInDb).getOrDefault(oreType, 0) + amount);
                    }
                }

                StringBuilder statMsg = new StringBuilder();
                statMsg.append("📝 Статистика на всех серверах `CivCraft` за последние `30` дней!\n\n");

                if (serverOreStats.isEmpty()) {
                    messageService.sendMessage(chatId, threadId, "❌ За этот период нет записей.");
                    return;
                }

                for (String server : serverOreStats.keySet()) {
                    String serverName = server.substring(0, 1).toUpperCase() + server.substring(1);
                    HashMap<String, Integer> oreStats = serverOreStats.get(server);
                    HashMap<String, Integer> playerCounts = serverPlayerCounts.get(server);
                    HashSet<String> uniquePlayers = serverUniquePlayers.get(server);

                    statMsg.append("Статистика по серверу `").append(serverName).append("`:\n");
                    statMsg.append("⛏️ Обнаружено кротов: `").append(uniquePlayers.size()).append("` ;\n");
                    statMsg.append("💎 Всего вскопанной руды: `")
                            .append(oreStats.values().stream().mapToInt(Integer::intValue).sum()).append("`, из них:\n\n");

                    for (String ore : new String[]{"IRON", "GOLD", "DIAMOND", "EMERALD"}) {
                        int count = 0;
                        for (String oreType : oreStats.keySet()) {
                            if (oreType.startsWith(ore)) {
                                count += oreStats.get(oreType);
                            }
                        }
                        statMsg.append("    • ").append(OreNamed(ore)).append(": `").append(count).append("` ;\n");
                    }

                    statMsg.append("\nСтатистика по кротам\n");
                    statMsg.append("👤 Настоящие кроты:\n");
                    for (String player : playerCounts.keySet()) {
                        statMsg.append("    • `").append(player).append("` - обнаружен ").append(playerCounts.get(player)).append(" раз!\n");
                    }
                    statMsg.append("\n\n");
                }

                messageService.sendMessage(chatId, threadId, statMsg.toString());

            }
        } catch (Exception e) {
            e.printStackTrace();
            messageService.sendMessage(chatId, threadId, "❌ Произошла ошибка при обработке запроса.");
        }
    }

    private void sendSpecificMonthAllServersStats(Long chatId, Integer threadId, String month) {
        String monthName = month.substring(0, 1).toUpperCase() + month.substring(1);
        Map<String, String[]> months = getMonthDateRanges();
        String[] monthRange = months.get(month.toLowerCase());

        if (monthRange == null) {
            messageService.sendMessage(chatId, threadId, "❌ Неверный месяц!");
            return;
        }

        String startDate = monthRange[0];
        String endDate = monthRange[1];

        SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy");
        try {
            Date start = dateFormat.parse(startDate);
            Date end = dateFormat.parse(endDate);
            long startMillis = start.getTime();
            long endMillis = end.getTime();

            Connection conn = SQL.getConnection();
            String query = "SELECT * FROM GLOBAL_SESSIONS WHERE time BETWEEN ? AND ?";

            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setLong(1, startMillis);
                ps.setLong(2, endMillis);

                ResultSet rs = ps.executeQuery();

                HashMap<String, HashMap<String, Integer>> oreStats = new HashMap<>();
                HashMap<String, HashMap<String, Integer>> playerCounts = new HashMap<>();
                HashMap<String, HashSet<String>> uniquePlayers = new HashMap<>();

                while (rs.next()) {
                    String key = rs.getString("key");
                    String value = rs.getString("value");

                    if (!key.contains(":")) continue;
                    String[] keyParts = key.split(":", 2);
                    String serverInDb = keyParts[0];
                    String player = keyParts[1];

                    uniquePlayers.putIfAbsent(serverInDb, new HashSet<>());
                    playerCounts.putIfAbsent(serverInDb, new HashMap<>());
                    oreStats.putIfAbsent(serverInDb, new HashMap<>());

                    uniquePlayers.get(serverInDb).add(player);
                    playerCounts.get(serverInDb).put(player, playerCounts.get(serverInDb).getOrDefault(player, 0) + 1);

                    if (!value.contains(":")) continue;
                    String[] valParts = value.split(":", 2);
                    String oreType = valParts[0];
                    int amount = Integer.parseInt(valParts[1]);

                    if (oreType.endsWith("_ORE")) {
                        oreStats.get(serverInDb).put(oreType, oreStats.get(serverInDb).getOrDefault(oreType, 0) + amount);
                    }
                }

                StringBuilder statMsg = new StringBuilder();
                statMsg.append("📝 Статистика по кротам на всех серверах `CivCraft` за `").append(monthName).append("` 2025 года\n\n");

                for (String server : oreStats.keySet()) {
                    statMsg.append("̲С̲т̲а̲т̲и̲с̲т̲и̲к̲а̲ ̲п̲о̲ ̲д̲о̲б̲ы̲ч̲е̲ ̲н̲а̲ ̲`").append(server.substring(0, 1).toUpperCase()).append(server.substring(1)).append("`\n");
                    statMsg.append("⛏️ Обнаружено кротов: `").append(uniquePlayers.get(server).size()).append("`;\n");
                    statMsg.append("💎 Всего вскопанной руды: `")
                            .append(oreStats.get(server).values().stream().mapToInt(Integer::intValue).sum()).append("`, из них:\n");

                    for (String ore : new String[]{"IRON", "GOLD", "DIAMOND", "EMERALD"}) {
                        int count = 0;
                        for (String oreType : oreStats.get(server).keySet()) {
                            if (oreType.startsWith(ore)) {
                                count += oreStats.get(server).get(oreType);
                            }
                        }
                        statMsg.append("    • ").append(OreNamed(ore)).append(": `").append(count).append("` ;\n");
                    }

                    statMsg.append("\nСтатистика по кротам\n");
                    statMsg.append("👤 Настоящие кроты:\n");
                    for (String player : playerCounts.get(server).keySet()) {
                        statMsg.append("    • `").append(player).append("` - обнаружен ")
                                .append(playerCounts.get(server).get(player)).append(" раз!\n");
                    }
                    statMsg.append("\n\n");
                }

                messageService.sendMessage(chatId, threadId, statMsg.toString());
            }

        } catch (Exception e) {
            e.printStackTrace();
            messageService.sendMessage(chatId, threadId, "❌ Произошла ошибка при обработке запроса.");
        }
    }

    private void sendCurrentMonthServerStats(Long chatId, Integer threadId, String server) {
        String serverName = server.substring(0, 1).toUpperCase() + server.substring(1);

        Calendar calendar = Calendar.getInstance();
        int currentMonth = calendar.get(Calendar.MONTH);
        int currentYear = calendar.get(Calendar.YEAR);

        String[] monthNames = {"январь", "февраль", "март", "апрель", "май", "июнь",
                "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"};
        String month = monthNames[currentMonth];

        Map<String, String[]> months = getMonthDateRanges();
        String[] range = months.get(month.toLowerCase());

        if (range == null) {
            messageService.sendMessage(chatId, threadId, "❌ Не удалось получить диапазон дат для месяца: " + month);
            return;
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy");
        try {
            Date startDate = dateFormat.parse(range[0]);
            Date endDate = dateFormat.parse(range[1]);

            long startMillis = startDate.getTime();
            long endMillis = endDate.getTime();

            Connection conn = SQL.getConnection();
            String query = "SELECT * FROM GLOBAL_SESSIONS WHERE time BETWEEN ? AND ? AND `key` LIKE ?";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setLong(1, startMillis);
                ps.setLong(2, endMillis);
                ps.setString(3, server.toLowerCase() + "%");

                ResultSet rs = ps.executeQuery();

                HashMap<String, Integer> oreStats = new HashMap<>();
                HashMap<String, Integer> playerCounts = new HashMap<>();
                HashSet<String> uniquePlayers = new HashSet<>();

                while (rs.next()) {
                    String key = rs.getString("key");
                    String value = rs.getString("value");

                    if (!key.contains(":")) continue;
                    String[] keyParts = key.split(":", 2);
                    String serverInDb = keyParts[0];
                    String player = keyParts[1];

                    if (!serverInDb.equalsIgnoreCase(server)) continue;

                    uniquePlayers.add(player);
                    playerCounts.put(player, playerCounts.getOrDefault(player, 0) + 1);

                    if (!value.contains(":")) continue;
                    String[] valParts = value.split(":", 2);
                    String oreType = valParts[0];
                    int amount = Integer.parseInt(valParts[1]);

                    if (oreType.endsWith("_ORE")) {
                        oreStats.put(oreType, oreStats.getOrDefault(oreType, 0) + amount);
                    }
                }

                if (uniquePlayers.isEmpty()) {
                    messageService.sendMessage(chatId, threadId, "❌ За этот период нет записей по серверу " + server);
                    return;
                }

                StringBuilder statMsg = new StringBuilder();
                statMsg.append("📝 Статистика по серверу `").append(serverName).append("` за последние `30` дней!\n\n");
                statMsg.append("̲С̲т̲а̲т̲и̲с̲т̲и̲к̲а̲ ̲п̲о̲ ̲д̲о̲б̲ы̲ч̲е̲ ̲н̲а̲ ̲`").append(serverName).append("` \n");
                statMsg.append("⛏️ Обнаружено кротов: `").append(uniquePlayers.size()).append("` ;\n");
                statMsg.append("💎 Всего вскопанной руды: `")
                        .append(oreStats.values().stream().mapToInt(Integer::intValue).sum()).append("`, из них:\n\n");

                for (String ore : new String[]{"IRON", "GOLD", "DIAMOND", "EMERALD"}) {
                    int count = 0;
                    for (String oreType : oreStats.keySet()) {
                        if (oreType.startsWith(ore)) {
                            count += oreStats.get(oreType);
                        }
                    }
                    statMsg.append("    • ").append(OreNamed(ore)).append(": `").append(count).append("` ;\n");
                }

                statMsg.append("\nСтатистика по кротам\n");
                statMsg.append("👤 Настоящие кроты:\n");
                for (String player : playerCounts.keySet()) {
                    statMsg.append("    • `").append(player).append("` - обнаружен ").append(playerCounts.get(player)).append(" раз!\n");
                }

                statMsg.append("\n#").append(server.toLowerCase()).append(" #statistics");

                messageService.sendMessage(chatId, threadId, statMsg.toString());

            }
        } catch (Exception e) {
            e.printStackTrace();
            messageService.sendMessage(chatId, threadId, "❌ Произошла ошибка при обработке запроса.");
        }
    }

    private void sendSpecificMonthServerStats(Long chatId, Integer threadId, String server, String month) {
        String serverName = server.substring(0, 1).toUpperCase() + server.substring(1);
        String monthName = month.substring(0, 1).toUpperCase() + month.substring(1);

        Map<String, String[]> months = getMonthDateRanges();
        String[] dateRange = months.get(month.toLowerCase());

        if (dateRange == null) {
            messageService.sendMessage(chatId, threadId, "❌ Указан некорректный месяц: " + month);
            return;
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy");
        try {
            Date startDate = dateFormat.parse(dateRange[0]);
            Date endDate = dateFormat.parse(dateRange[1]);
            long startMillis = startDate.getTime();
            long endMillis = endDate.getTime();

            Connection conn = SQL.getConnection();
            String query = "SELECT * FROM GLOBAL_SESSIONS WHERE time BETWEEN ? AND ? AND `key` LIKE ?";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setLong(1, startMillis);
                ps.setLong(2, endMillis);
                ps.setString(3, server.toLowerCase() + "%");

                ResultSet rs = ps.executeQuery();

                HashMap<String, Integer> oreStats = new HashMap<>();
                HashMap<String, Integer> playerCounts = new HashMap<>();
                HashSet<String> uniquePlayers = new HashSet<>();

                while (rs.next()) {
                    String key = rs.getString("key");
                    String value = rs.getString("value");

                    if (!key.contains(":")) continue;
                    String[] keyParts = key.split(":", 2);
                    String serverInDb = keyParts[0];
                    String player = keyParts[1];

                    if (!serverInDb.equalsIgnoreCase(server)) continue;

                    uniquePlayers.add(player);
                    playerCounts.put(player, playerCounts.getOrDefault(player, 0) + 1);

                    if (!value.contains(":")) continue;
                    String[] valParts = value.split(":", 2);
                    String oreType = valParts[0];
                    int amount = Integer.parseInt(valParts[1]);

                    if (oreType.endsWith("_ORE")) {
                        oreStats.put(oreType, oreStats.getOrDefault(oreType, 0) + amount);
                    }
                }

                if (uniquePlayers.isEmpty()) {
                    messageService.sendMessage(chatId, threadId, "❌ За этот период нет записей по серверу " + server);
                    return;
                }

                StringBuilder statMsg = new StringBuilder();
                statMsg.append("📝 Статистика по серверу `").append(serverName).append("` за `").append(monthName).append("` 2025 года!\n\n");

                statMsg.append("̲С̲т̲а̲т̲и̲с̲т̲и̲к̲а̲ ̲п̲о̲ ̲д̲о̲б̲ы̲ч̲е̲ ̲н̲а̲ ̲`").append(serverName).append("` \n");
                statMsg.append("⛏️ Обнаружено кротов: `").append(uniquePlayers.size()).append("` ;\n");
                statMsg.append("💎 Всего вскопанной руды: `")
                        .append(oreStats.values().stream().mapToInt(Integer::intValue).sum()).append("`, из них:\n\n");

                for (String ore : new String[]{"IRON", "GOLD", "DIAMOND", "EMERALD"}) {
                    int count = 0;
                    for (String oreType : oreStats.keySet()) {
                        if (oreType.startsWith(ore)) {
                            count += oreStats.get(oreType);
                        }
                    }
                    statMsg.append("    • ").append(OreNamed(ore)).append(": `").append(count).append("` ;\n");
                }

                statMsg.append("\nСтатистика по кротам\n");
                statMsg.append("👤 Настоящие кроты:\n");
                for (String player : playerCounts.keySet()) {
                    statMsg.append("    • `").append(player).append("` - обнаружен ").append(playerCounts.get(player)).append(" раз!\n");
                }

                statMsg.append("\n#").append(server.toLowerCase()).append(" #statistics");

                messageService.sendMessage(chatId, threadId, statMsg.toString());
            }
        } catch (Exception e) {
            e.printStackTrace();
            messageService.sendMessage(chatId, threadId, "❌ Произошла ошибка при обработке запроса.");
        }
    }

    private String OreNamed(String code) {
        return switch (code.toUpperCase()) {
            case "IRON" -> "Железной";
            case "GOLD" -> "Золотой";
            case "DIAMOND" -> "Алмазной";
            case "EMERALD" -> "Изумрудня";
            default -> code;
        };
    }

    private static Map<String, String[]> getMonthDateRanges() {
        Map<String, String[]> months = new HashMap<>();
        months.put("январь", new String[]{"01-01-2025", "31-01-2025"});
        months.put("февраль", new String[]{"01-02-2025", "28-02-2025"});
        months.put("март", new String[]{"01-03-2025", "31-03-2025"});
        months.put("апрель", new String[]{"01-04-2025", "30-04-2025"});
        months.put("май", new String[]{"01-05-2025", "31-05-2025"});
        months.put("июнь", new String[]{"01-06-2025", "30-06-2025"});
        months.put("июль", new String[]{"01-07-2025", "31-07-2025"});
        months.put("август", new String[]{"01-08-2025", "31-08-2025"});
        months.put("сентябрь", new String[]{"01-09-2025", "30-09-2025"});
        months.put("октябрь", new String[]{"01-10-2025", "31-10-2025"});
        months.put("ноябрь", new String[]{"01-11-2025", "30-11-2025"});
        months.put("декабрь", new String[]{"01-12-2025", "31-12-2025"});
        return months;
    }

}