package haunt.trust.commands;

import haunt.trust.config.ConfigLoader;
import haunt.trust.game.ActionLog;
import haunt.trust.game.ActionLog.ActionLogEntry;
import haunt.trust.game.ActionLog.ActionQuery;
import haunt.trust.utils.MessageService;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ActionLogCommand {
    private static final int PAGE_SIZE = 8;
    private static final int MAX_PAGE = 100;
    private static final int CACHE_LIMIT = PAGE_SIZE * (MAX_PAGE + 1);
    private static final Map<String, CachedQuery> QUERY_CACHE = new ConcurrentHashMap<>();

    private final MessageService messageService;
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("dd.MM.yyyy");

    public ActionLogCommand(MessageService messageService) {
        this.messageService = messageService;
        this.dayFormat.setLenient(false);
    }

    public void handleActionCommand(Message msg, Long chatId, Integer threadId, String text) {
        if (!isUserAuthorized(msg.getFrom().getId())) {
            return;
        }

        try {
            ParsedCommand parsed = parse(text);
            if (parsed.mode == Mode.HELP) {
                showHelp(chatId, threadId);
                return;
            }
            if (parsed.mode == Mode.STATS) {
                showStats(chatId, threadId, parsed.query.days);
                return;
            }
            if (parsed.mode == Mode.RAW) {
                showRawJson(parsed.rawId, chatId, threadId);
                return;
            }
            sendInitialPage(chatId, threadId, msg.getFrom().getId(), parsed.query, parsed.page);
        } catch (IllegalArgumentException e) {
            messageService.sendMessageHtml(chatId, threadId, "Ошибка: " + html(e.getMessage()) + "\n\n" + helpCompact());
        } catch (SQLException e) {
            messageService.sendMessageHtml(chatId, threadId, "Ошибка базы данных: " + html(e.getMessage()));
        } catch (Exception e) {
            messageService.sendMessageHtml(chatId, threadId, "Ошибка: " + html(e.getMessage()));
        }
    }

    public boolean handleCallbackQuery(CallbackQuery callbackQuery) {
        String data = callbackQuery.getData();
        if (data == null || (!data.startsWith("act:") && !data.startsWith("actnoop:"))) {
            return false;
        }

        if (!isUserAuthorized(callbackQuery.getFrom().getId())) {
            messageService.answerCallback(callbackQuery.getId(), "Нет прав", true);
            return true;
        }

        if (data.startsWith("actnoop:")) {
            String[] noopParts = data.split(":");
            if (noopParts.length == 3) {
                messageService.answerCallback(callbackQuery.getId(), "Страница " + noopParts[2], false);
            } else {
                messageService.answerCallback(callbackQuery.getId(), "Страница", false);
            }
            return true;
        }

        String[] parts = data.split(":");
        if (parts.length != 3) {
            messageService.answerCallback(callbackQuery.getId(), "Кнопка устарела", true);
            return true;
        }

        CachedQuery cached = QUERY_CACHE.get(parts[1]);
        if (cached == null) {
            messageService.answerCallback(callbackQuery.getId(), "Поиск устарел, отправь команду заново", true);
            return true;
        }

        if (!cached.userId.equals(callbackQuery.getFrom().getId())) {
            messageService.answerCallback(callbackQuery.getId(), "Это не твой поиск", true);
            return true;
        }

        int page;
        try {
            page = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            messageService.answerCallback(callbackQuery.getId(), "Неверная страница", true);
            return true;
        }

        if (!(callbackQuery.getMessage() instanceof Message message)) {
            messageService.answerCallback(callbackQuery.getId(), "Не могу обновить сообщение", true);
            return true;
        }

        if (!cached.chatId.equals(message.getChatId()) || cached.messageId == null || !cached.messageId.equals(message.getMessageId())) {
            messageService.answerCallback(callbackQuery.getId(), "Кнопка не от этого сообщения", true);
            return true;
        }

        try {
            editCachedPage(message.getChatId(), message.getMessageId(), cached, page);
            messageService.answerCallback(callbackQuery.getId(), "Страница " + (page + 1), false);
        } catch (Exception e) {
            messageService.answerCallback(callbackQuery.getId(), "Ошибка: " + e.getMessage(), true);
        }
        return true;
    }

    private ParsedCommand parse(String text) throws ParseException {
        String[] split = text.trim().split("\\s+");
        String command = split[0].toLowerCase(Locale.ROOT);
        List<String> args = new ArrayList<>(Arrays.asList(split).subList(1, split.length));

        ParsedCommand parsed = new ParsedCommand();
        parsed.query = new ActionQuery();
        parsed.query.limit = PAGE_SIZE;

        if (command.equals("/action_search")) {
            requireArgs(args, "/action_search <ник>");
            parsed.query.playerName = args.get(0);
            return parsed;
        }
        if (command.equals("/action_auction")) {
            parsed.query.actionTypes.add("auction");
            return parsed;
        }
        if (command.equals("/action_recent")) {
            return parsed;
        }
        if (command.equals("/action_date")) {
            requireArgs(args, "/action_date 01.05.2026-15.05.2026");
            applyDateRange(parsed.query, args.get(0));
            return parsed;
        }
        if (command.equals("/action_stats")) {
            parsed.mode = Mode.STATS;
            parsed.query.days = parseDays(args, 7);
            return parsed;
        }
        if (command.equals("/action_raw")) {
            requireArgs(args, "/action_raw <id>");
            parsed.mode = Mode.RAW;
            parsed.rawId = parseLong(args.get(0), "ID должен быть числом");
            return parsed;
        }

        if (args.isEmpty()) {
            parsed.mode = Mode.HELP;
            return parsed;
        }

        String first = args.get(0).toLowerCase(Locale.ROOT);
        if (first.equals("help") || first.equals("помощь")) {
            parsed.mode = Mode.HELP;
            return parsed;
        }
        if (first.equals("stats") || first.equals("стата")) {
            parsed.mode = Mode.STATS;
            parsed.query.days = parseDays(args.subList(1, args.size()), 7);
            return parsed;
        }
        if (first.equals("raw")) {
            if (args.size() < 2) {
                throw new IllegalArgumentException("Использование: /action raw <id>");
            }
            parsed.mode = Mode.RAW;
            parsed.rawId = parseLong(args.get(1), "ID должен быть числом");
            return parsed;
        }
        if (first.equals("recent") || first.equals("last") || first.equals("последние")) {
            args = args.subList(1, args.size());
        }

        for (String arg : args) {
            if (arg.isBlank()) {
                continue;
            }

            String lower = arg.toLowerCase(Locale.ROOT);
            if (lower.startsWith("u:") || lower.startsWith("user:") || lower.startsWith("ник:")) {
                parsed.query.playerName = valueAfterColon(arg);
            } else if (lower.startsWith("a:") || lower.startsWith("action:") || lower.startsWith("тип:")) {
                addActionTypes(parsed.query, valueAfterColon(arg));
            } else if (lower.startsWith("d:") || lower.startsWith("days:") || lower.startsWith("дней:")) {
                parsed.query.days = parseInt(valueAfterColon(arg), "Дни должны быть числом");
            } else if (lower.startsWith("date:") || lower.startsWith("дата:")) {
                applyDateRange(parsed.query, valueAfterColon(arg));
            } else if (lower.startsWith("p:") || lower.startsWith("page:") || lower.startsWith("стр:")) {
                parsed.page = Math.max(0, parseInt(valueAfterColon(arg), "Страница должна быть числом") - 1);
            } else if (lower.startsWith("id:")) {
                parsed.query.id = parseLong(valueAfterColon(arg), "ID должен быть числом");
            } else if (looksLikeDays(lower)) {
                parsed.query.days = parseInt(lower.substring(0, lower.length() - 1), "Дни должны быть числом");
            } else if (looksLikePage(lower)) {
                parsed.page = Math.max(0, parseInt(lower.substring(1), "Страница должна быть числом") - 1);
            } else if (looksLikeDateRange(arg)) {
                applyDateRange(parsed.query, arg);
            } else if (isKnownActionAlias(lower)) {
                addActionTypes(parsed.query, lower);
            } else if (parsed.query.playerName == null) {
                parsed.query.playerName = arg;
            } else {
                throw new IllegalArgumentException("Не понял фильтр: " + arg);
            }
        }

        if (parsed.page > MAX_PAGE) {
            parsed.page = MAX_PAGE;
        }
        return parsed;
    }

    private void sendInitialPage(Long chatId, Integer threadId, Long userId, ActionQuery query, int page) throws SQLException {
        int total = ActionLog.count(query);
        query.offset = 0;
        query.limit = CACHE_LIMIT;

        List<ActionLogEntry> allResults = ActionLog.search(query);
        String token = cacheQuery(query, allResults, total, userId, chatId);
        CachedQuery cached = QUERY_CACHE.get(token);
        PageData pageData = getCachedPage(cached, page);

        String messageText = formatPage(cached.query, pageData.results, cached.total, pageData.page, pageData.totalPages);
        InlineKeyboardMarkup keyboard = createPaginationKeyboard(token, pageData.page, pageData.totalPages);
        Integer messageId = messageService.sendMessageHtmlWithResult(chatId, threadId, messageText, keyboard);
        if (messageId != null) {
            cached.messageId = messageId;
        }
    }

    private void editCachedPage(Long chatId, Integer messageId, CachedQuery cached, int page) {
        PageData pageData = getCachedPage(cached, page);
        String messageText = formatPage(cached.query, pageData.results, cached.total, pageData.page, pageData.totalPages);
        InlineKeyboardMarkup keyboard = createPaginationKeyboard(cached.token, pageData.page, pageData.totalPages);
        messageService.editMessageHtml(chatId, messageId, messageText, keyboard);
    }

    private PageData getCachedPage(CachedQuery cached, int page) {
        int totalPages = Math.max(1, (int) Math.ceil(cached.total / (double) PAGE_SIZE));
        totalPages = Math.min(totalPages, Math.max(1, (int) Math.ceil(cached.results.size() / (double) PAGE_SIZE)));
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        int from = Math.min(safePage * PAGE_SIZE, cached.results.size());
        int to = Math.min(from + PAGE_SIZE, cached.results.size());
        return new PageData(cached.results.subList(from, to), safePage, totalPages);
    }

    private String formatPage(ActionQuery query, List<ActionLogEntry> results, int total, int page, int totalPages) {
        StringBuilder response = new StringBuilder();
        response.append("<b>Логи действий</b>\n");
        response.append("<b>Фильтр:</b> ").append(html(describeQuery(query))).append("\n");
        response.append("<b>Найдено:</b> ").append(total).append(" | <b>Страница:</b> ").append(page + 1).append("/").append(totalPages).append("\n\n");

        if (results.isEmpty()) {
            return response.append("Ничего не найдено.").toString();
        }

        int index = page * PAGE_SIZE + 1;
        for (ActionLogEntry entry : results) {
            response.append("<b>").append(index++).append(".</b> ")
                    .append(entry.toTelegramString())
                    .append("\n");
        }

        response.append("\n<i>/action raw ID - сырой JSON</i>");
        return response.toString();
    }

    private String describeQuery(ActionQuery query) {
        List<String> parts = new ArrayList<>();
        if (query.id != null) {
            parts.add("id " + query.id);
        }
        if (query.playerName != null) {
            parts.add("игрок " + query.playerName);
        }
        if (!query.actionTypes.isEmpty()) {
            parts.add("тип " + String.join(",", query.actionTypes));
        }
        if (query.startDate != null && query.endDate != null) {
            parts.add(dayFormat.format(query.startDate) + "-" + dayFormat.format(query.endDate));
        } else if (query.days > 0) {
            parts.add("за " + query.days + " дн.");
        }
        return parts.isEmpty() ? "последние действия" : String.join(", ", parts);
    }

    private void showHelp(Long chatId, Integer threadId) {
        messageService.sendMessageHtml(chatId, threadId, helpText());
    }

    private InlineKeyboardMarkup createPaginationKeyboard(String token, int page, int totalPages) {
        if (totalPages <= 1) {
            return null;
        }

        InlineKeyboardRow row = new InlineKeyboardRow();
        if (page > 0) {
            row.add(InlineKeyboardButton.builder().text("<").callbackData("act:" + token + ":" + (page - 1)).build());
        }
        row.add(InlineKeyboardButton.builder().text((page + 1) + "/" + totalPages).callbackData("actnoop:" + token + ":" + (page + 1) + "/" + totalPages).build());
        if (page + 1 < totalPages) {
            row.add(InlineKeyboardButton.builder().text(">").callbackData("act:" + token + ":" + (page + 1)).build());
        }
        return InlineKeyboardMarkup.builder().keyboard(List.of(row)).build();
    }

    private String helpText() {
        return """
                <b>Логи действий</b>

                <b>Помощь:</b>
                /action Nick
                /action Nick pay
                /action Nick town
                /action Nick pay 7d
                /action pay
                /action auction
                /action recent
                /action stats
                /action raw 12345

                <b>По датам:</b>
                /action Nick 01.05.2026-15.05.2026
                /action auction 01.05.2026-15.05.2026

                <b>Типы:</b>
                pay, auction, pickup, transfer, town, join, leave
                """;
    }

    private String helpCompact() {
        return "Пример: /action Nick, /action Nick pay 7d, /action Nick town";
    }

    private void showStats(Long chatId, Integer threadId, int days) throws SQLException {
        int safeDays = days <= 0 ? 7 : days;
        ActionLog.ActionStats stats = ActionLog.getStats(safeDays);

        String text = String.format(Locale.US, """
                <b>Статистика логов за %d дн.</b>

                Всего действий: %d
                /pay: %d на %,.0f монет
                Аукцион: %d на %,.0f монет
                Подборы: %d
                Передачи: %d
                Вступления в города: %d
                Выходы из городов: %d
                """,
                safeDays,
                stats.totalActions,
                stats.payCount,
                stats.payVolume,
                stats.auctionCount,
                stats.auctionVolume,
                stats.pickupCount,
                stats.transferCount,
                stats.townJoinCount,
                stats.townLeaveCount);

        messageService.sendMessageHtml(chatId, threadId, text);
    }

    private void showRawJson(long id, Long chatId, Integer threadId) throws SQLException {
        String json = ActionLog.getRawJson(id);
        if (json == null) {
            messageService.sendMessageHtml(chatId, threadId, "Запись с ID " + id + " не найдена.");
            return;
        }

        String formatted = formatJson(json);
        if (formatted.length() > 3800) {
            formatted = formatted.substring(0, 3800) + "\n... обрезано";
        }
        messageService.sendMessage(chatId, threadId, "```json\n" + formatted + "\n```");
    }

    private String formatJson(String json) {
        try {
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            com.google.gson.JsonElement element = new com.google.gson.JsonParser().parse(json);
            return gson.toJson(element);
        } catch (Exception e) {
            return json;
        }
    }

    private void applyDateRange(ActionQuery query, String value) throws ParseException {
        String[] dates = value.split("-");
        if (dates.length != 2) {
            throw new IllegalArgumentException("Дата должна быть в формате 01.05.2026-15.05.2026");
        }
        Date start = dayFormat.parse(dates[0].trim());
        Date end = dayFormat.parse(dates[1].trim());
        query.startDate = new Date(start.getTime());
        query.endDate = new Date(end.getTime() + 86_399_999L);
    }

    private void addActionTypes(ActionQuery query, String value) {
        for (String token : value.split(",")) {
            List<String> mapped = mapActionAlias(token.trim().toLowerCase(Locale.ROOT));
            if (mapped.isEmpty()) {
                throw new IllegalArgumentException("Неизвестный тип действия: " + token);
            }
            query.actionTypes.addAll(mapped);
        }
    }

    private boolean isKnownActionAlias(String value) {
        return !mapActionAlias(value).isEmpty();
    }

    private List<String> mapActionAlias(String value) {
        return switch (value) {
            case "pay", "money", "пей", "деньги", "player_pay" -> List.of("player_pay");
            case "auction", "auc", "аук", "аукцион" -> List.of("auction");
            case "pickup", "pick", "подбор", "поднял" -> List.of("pickup");
            case "transfer", "give", "передача", "trade" -> List.of("transfer");
            case "town", "город" -> List.of("town_join", "town_leave");
            case "join", "town_join", "вступил" -> List.of("town_join");
            case "leave", "town_leave", "вышел" -> List.of("town_leave");
            default -> List.of();
        };
    }

    private String cacheQuery(ActionQuery query, List<ActionLogEntry> results, int total, Long userId, Long chatId) {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        QUERY_CACHE.put(token, new CachedQuery(token, query.copy(), List.copyOf(results), total, userId, chatId, System.currentTimeMillis()));
        cleanupCache();
        return token;
    }

    private void cleanupCache() {
        long expiresBefore = System.currentTimeMillis() - 30 * 60 * 1000L;
        QUERY_CACHE.entrySet().removeIf(entry -> entry.getValue().createdAt < expiresBefore);
    }

    private boolean looksLikeDateRange(String value) {
        return value.matches("\\d{2}\\.\\d{2}\\.\\d{4}-\\d{2}\\.\\d{2}\\.\\d{4}");
    }

    private boolean looksLikeDays(String value) {
        return value.matches("\\d+d");
    }

    private boolean looksLikePage(String value) {
        return value.matches("p\\d+");
    }

    private String valueAfterColon(String value) {
        int index = value.indexOf(':');
        if (index < 0 || index == value.length() - 1) {
            throw new IllegalArgumentException("Пустой фильтр: " + value);
        }
        return value.substring(index + 1).trim();
    }

    private int parseDays(List<String> args, int fallback) {
        if (args.isEmpty()) {
            return fallback;
        }
        return parseInt(args.get(0), "Дни должны быть числом");
    }

    private int parseInt(String value, String error) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(error);
        }
    }

    private long parseLong(String value, String error) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(error);
        }
    }

    private void requireArgs(List<String> args, String usage) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("Использование: " + usage);
        }
    }

    private boolean isUserAuthorized(Long userId) {
        return ConfigLoader.config.getAutorized_users().contains(userId);
    }

    private String html(String value) {
        return MessageService.escapeHtml(value);
    }

    private enum Mode {
        SEARCH,
        HELP,
        STATS,
        RAW
    }

    private static class ParsedCommand {
        Mode mode = Mode.SEARCH;
        ActionQuery query;
        int page;
        long rawId;
    }

    private static class CachedQuery {
        private final String token;
        private final ActionQuery query;
        private final List<ActionLogEntry> results;
        private final int total;
        private final Long userId;
        private final Long chatId;
        private final long createdAt;
        private Integer messageId;

        private CachedQuery(String token, ActionQuery query, List<ActionLogEntry> results, int total, Long userId, Long chatId, long createdAt) {
            this.token = token;
            this.query = query;
            this.results = results;
            this.total = total;
            this.userId = userId;
            this.chatId = chatId;
            this.createdAt = createdAt;
        }
    }

    private record PageData(List<ActionLogEntry> results, int page, int totalPages) {
    }
}
