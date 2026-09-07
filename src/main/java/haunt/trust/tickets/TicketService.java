package haunt.trust.tickets;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import haunt.trust.commands.CreateKeyboardUser;
import haunt.trust.database.SQL;
import haunt.trust.utils.MessageService;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TicketService {
    private final MessageService messageService;
    private static final long SUPPORT_CHAT_ID = -1002521750905L;
    private static final int SUPPORT_THREAD_ID = 5417;
    private static final long DEVELOPER_ID = 1137489984L;

    // Время жизни тикета после последнего ответа поддержки (24 часа)
    private static final long TICKET_EXPIRY_HOURS = 24;

    // Храним состояние пользователей: userId -> TicketState
    private final ConcurrentHashMap<Long, TicketState> userStates = new ConcurrentHashMap<>();

    // Храним созданные тикеты: ticketId -> TicketDataExtended
    private final ConcurrentHashMap<String, TicketDataExtended> tickets = new ConcurrentHashMap<>();

    // Храним связь между messageId в треде и userId/ticketId
    private final ConcurrentHashMap<Integer, TicketMessageLink> messageLinks = new ConcurrentHashMap<>();

    // Храним историю сообщений для каждого тикета (ticketId -> Map<messageId, link>)
    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, TicketMessageLink>> ticketMessageHistory = new ConcurrentHashMap<>();

    // Храним на какой тикет пользователь хочет ответить
    private final ConcurrentHashMap<Long, String> userReplyToTicket = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ConcurrentHashMap<Integer, String>> userPrivateMessageLinks = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Long, String> userCloseConfirmation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> pendingTicketCloseSelection = new ConcurrentHashMap<>();

    // Для хранения медиафайлов от пользователей при ответах
    private final ConcurrentHashMap<Long, UserMediaReply> pendingMediaReplies = new ConcurrentHashMap<>();

    // Список заблокированных пользователей (по Telegram ID и username)
    private final Set<Long> bannedUserIds = ConcurrentHashMap.newKeySet();
    private final Set<String> bannedUsernames = ConcurrentHashMap.newKeySet();
    private final Set<Long> shadowTicketCreationUserIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, TicketDataExtended> shadowTicketsByUserId = new ConcurrentHashMap<>();

    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor();
    private boolean cleanupSchedulerStarted = false;
    private int ticketCounter = 1;

    public enum MessageType {
        INITIAL_TICKET,       // Первоначальное сообщение тикета
        SUPPORT_REPLY,        // Ответ поддержки
        USER_REPLY,           // Ответ пользователя
        MEDIA_MESSAGE         // Медиафайл
    }

    private static class UserMediaReply {
        private final String ticketId;
        private final String fileId;
        private final String mediaType;
        private final String caption;

        public UserMediaReply(String ticketId, String fileId, String mediaType, String caption) {
            this.ticketId = ticketId;
            this.fileId = fileId;
            this.mediaType = mediaType;
            this.caption = caption;
        }

        public String getTicketId() { return ticketId; }
        public String getFileId() { return fileId; }
        public String getMediaType() { return mediaType; }
        public String getCaption() { return caption; }
    }

    private static class TicketDataExtended {
        private final String ticketId;
        private final Long userId;
        private final String username;
        private final String issueDescription;
        private final String evidence;
        private final LocalDateTime createdAt;
        @Setter
        private LocalDateTime lastSupportReply;
        @Setter
        private LocalDateTime lastUserReply;
        @Setter
        private boolean closed;
        @Setter
        private String mediaFileId;
        @Setter
        private String mediaType;
        @Setter
        private String mediaCaption;

        public TicketDataExtended(String ticketId, Long userId, String username,
                                  String issueDescription, String evidence, LocalDateTime createdAt) {
            this.ticketId = ticketId;
            this.userId = userId;
            this.username = username;
            this.issueDescription = issueDescription;
            this.evidence = evidence;
            this.createdAt = createdAt;
            this.lastSupportReply = null;
            this.lastUserReply = null;
            this.closed = false;
        }

        public String getTicketId() { return ticketId; }
        public Long getUserId() { return userId; }
        public String getUsername() { return username; }
        public String getIssueDescription() { return issueDescription; }
        public String getEvidence() { return evidence; }
        public LocalDateTime getCreatedAt() { return createdAt; }

        public String getMediaFileId() { return mediaFileId; }

        public String getMediaType() { return mediaType; }

        public String getMediaCaption() { return mediaCaption; }

        public boolean hasMedia() {
            return mediaFileId != null && !mediaFileId.isEmpty();
        }

        public LocalDateTime getLastSupportReply() { return lastSupportReply; }

        public LocalDateTime getLastUserReply() { return lastUserReply; }

        public boolean isClosed() { return closed; }

        // Проверка истечения срока (24 часа после последнего ответа поддержки)
        public boolean isExpired() {
            if (lastSupportReply == null) {
                return false;
            }
            return ChronoUnit.HOURS.between(lastSupportReply, LocalDateTime.now()) >= TICKET_EXPIRY_HOURS;
        }

        public LocalDateTime getExpiryTime() {
            if (lastSupportReply == null) {
                return null;
            }
            return lastSupportReply.plusHours(TICKET_EXPIRY_HOURS);
        }
    }

    public TicketService(MessageService messageService) {
        this.messageService = messageService;
        System.out.println("✅ TicketService инициализирован");
        loadTicketHistoriesFromDatabase();
    }

    private void loadTicketHistoriesFromDatabase() {
        int restoredCount = 0;
        int maxTicketNumber = 0;

        for (Map.Entry<Long, JsonArray> entry : SQL.getAllTicketHistories().entrySet()) {
            for (TicketDataExtended ticket : restoreTicketsFromHistory(entry.getKey(), entry.getValue())) {
                restoreTicketToMemory(ticket);
                maxTicketNumber = Math.max(maxTicketNumber, extractTicketNumber(ticket.getTicketId()));
                restoredCount++;
            }
        }

        ticketCounter = Math.max(ticketCounter, maxTicketNumber + 1);
        System.out.println("📂 Загружено обращений из БД: " + restoredCount);
    }

    private int extractTicketNumber(String ticketId) {
        if (ticketId == null || !ticketId.startsWith("TKT-")) {
            return 0;
        }

        try {
            return Integer.parseInt(ticketId.substring(4));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void restoreTicketToMemory(TicketDataExtended ticket) {
        if (ticket == null) {
            return;
        }

        tickets.put(ticket.getTicketId(), ticket);
        if (!ticket.isClosed()) {
            userReplyToTicket.put(ticket.getUserId(), ticket.getTicketId());
            startCleanupScheduler();
        }
    }

    public boolean isUserBanned(Long userId, String username) {
        if (userId != null && bannedUserIds.contains(userId)) {
            return true;
        }

        if (username != null) {
            String normalized = username.toLowerCase();
            if (!normalized.startsWith("@")) {
                normalized = "@" + normalized;
            }
            return bannedUsernames.contains(normalized);
        }

        return false;
    }

    public String banUser(String identifier) {
        if (identifier == null) {
            return "❌ <b>Использование:</b> /banned &lt;telegram_id | @username | username&gt;";
        }

        String value = identifier.trim();
        if (value.isEmpty()) {
            return "❌ <b>Использование:</b> /banned &lt;telegram_id | @username | username&gt;";
        }

        if (value.matches("\\d+")) {
            try {
                long id = Long.parseLong(value);
                bannedUserIds.add(id);
                return "🚫 <b>Пользователь с Telegram ID " + id + " добавлен в бан-лист тикетов.</b>";
            } catch (NumberFormatException e) {
                return "❌ Неверный формат Telegram ID.";
            }
        }

        String normalized = value.toLowerCase();
        if (!normalized.startsWith("@")) {
            normalized = "@" + normalized;
        }
        bannedUsernames.add(normalized);

        return "🚫 <b>Пользователь " + normalized + " добавлен в бан-лист тикетов.</b>";
    }

    public String unbanUser(String identifier) {
        if (identifier == null) {
            return "❌ <b>Использование:</b> /unban &lt;telegram_id | @username | username&gt;";
        }

        String value = identifier.trim();
        if (value.isEmpty()) {
            return "❌ <b>Использование:</b> /unban &lt;telegram_id | @username | username&gt;";
        }

        boolean removed = false;

        if (value.matches("\\d+")) {
            try {
                long id = Long.parseLong(value);
                removed = bannedUserIds.remove(id);
            } catch (NumberFormatException e) {
                return "❌ Неверный формат Telegram ID.";
            }
        } else {
            String normalized = value.toLowerCase();
            if (!normalized.startsWith("@")) {
                normalized = "@" + normalized;
            }
            removed = bannedUsernames.remove(normalized);
        }

        if (removed) {
            return "✅ <b>Пользователь разблокирован.</b>";
        } else {
            return "ℹ️ <b>Пользователь не найден в бан-листе.</b>";
        }
    }

    /**
     * Получить userId по messageId (из истории тикетов) если сообщение относится к тикету.
     */
    public Long getUserIdByMessageId(Integer messageId) {
        TicketMessageLink link = messageLinks.get(messageId);
        if (link == null) return null;
        return link.getUserId();
    }

    public Long getUserIdByMessageId(Integer messageId, String replyToText) {
        TicketMessageLink link = resolveSupportMessageLink(messageId, replyToText);
        if (link == null) return null;
        return link.getUserId();
    }

    public TicketUserInfo getUserInfoByMessageId(Integer messageId, String replyToText) {
        TicketMessageLink link = resolveSupportMessageLink(messageId, replyToText);
        if (link == null) return null;
        return new TicketUserInfo(link.getUserId(), link.getTicketId());
    }

    public record TicketUserInfo(Long telegramId, String ticketId) {}


    public void closeTicketByUserCommand(Long userId) {
        String activeTicketId = null;
        TicketDataExtended userTicket = null;

        for (TicketDataExtended ticket : tickets.values()) {
            if (ticket.getUserId().equals(userId) && !ticket.isClosed()) {
                activeTicketId = ticket.getTicketId();
                userTicket = ticket;
                break;
            }
        }
        if (userTicket == null) {
            TicketDataExtended shadowTicket = shadowTicketsByUserId.get(userId);
            if (shadowTicket != null && !shadowTicket.isClosed()) {
                activeTicketId = shadowTicket.getTicketId();
                userTicket = shadowTicket;
            }
        }

        if (activeTicketId == null) {
            messageService.sendMessageHtml(userId, null,
                    "❌ <b>У вас нет активных тикетов для закрытия.</b>\n\n" +
                            "Вы можете создать новый тикет через меню.");
            return;
        }

        String confirmMessage = String.format(
                "🔒 <b>Подтверждение закрытия тикета #%s</b>\n\n" +
                        "👤 <i>Пользователь:</i> %s\n" +
                        "📝 <i>Проблема:</i> %s\n\n" +
                        "Вы уверены, что хотите закрыть этот тикет?\n" +
                        "Напишите <code>да</code> для подтверждения или <code>нет</code> для отмены.",
                activeTicketId,
                userTicket.getUsername(),
                userTicket.getIssueDescription().length() > 100 ?
                        userTicket.getIssueDescription().substring(0, 100) + "..." :
                        userTicket.getIssueDescription()
        );

        messageService.sendMessageHtml(userId, null, confirmMessage);

        userCloseConfirmation.put(userId, activeTicketId);
    }

    private void startCleanupScheduler() {
        if (cleanupSchedulerStarted) {
            return;
        }

        cleanupSchedulerStarted = true;
        cleanupScheduler.scheduleAtFixedRate(() -> {
            try {
                cleanupExpiredTickets();
            } catch (Exception e) {
                System.err.println("❌ Ошибка при очистке тикетов: " + e.getMessage());
                e.printStackTrace();
            }
        }, 30, 30, TimeUnit.MINUTES);

        System.out.println("🔄 Планировщик очистки тикетов запущен");
    }

    private void cleanupExpiredTickets() {
        int expiredCount = 0;
        int totalTickets = tickets.size();

        for (Map.Entry<String, TicketDataExtended> entry : tickets.entrySet()) {
            String ticketId = entry.getKey();
            TicketDataExtended ticket = entry.getValue();

            if (!ticket.isClosed() && ticket.isExpired()) {
                closeTicket(ticketId, "автоматическое закрытие после 24 часов без ответа");
                expiredCount++;
            }
        }

        if (expiredCount > 0) {
            System.out.println("🧹 Автоматически закрыто " + expiredCount + " просроченных тикетов из " + totalTickets);
        }
    }

    public void closeTicket(String ticketId, String reason) {
        TicketDataExtended ticket = tickets.get(ticketId);
        if (ticket == null || ticket.isClosed()) {
            return;
        }

        ticket.setClosed(true);
        saveTicketHistory(ticket, "closed", reason, null);

        String closeMessage = String.format(
                "🔒 <b>Тикет #%s закрыт</b>\n" +
                        "👤 Пользователь: %s\n" +
                        "📅 Создан: %s\n" +
                        "📅 Закрыт: %s\n" +
                        "📝 Причина: %s\n",
                ticketId,
                ticket.getUsername(),
                ticket.getCreatedAt().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")),
                reason
        );

        messageService.sendMessageHtml(DEVELOPER_ID, null, closeMessage);

        if (userReplyToTicket.containsKey(ticket.getUserId())) {
            Integer userCloseMessageId = messageService.sendMessageHtmlWithResult(ticket.getUserId(), null,
                    "🔒 <b>Тикет #" + ticketId + " закрыт</b>\n\n" +
                            "📝 <i>Причина:</i> " + reason + "\n\n" +
                            "Спасибо за обращение! Если у вас возникнут новые вопросы, создайте новый тикет через /start");
            rememberUserMessageLink(ticket.getUserId(), userCloseMessageId, ticketId);

            userReplyToTicket.remove(ticket.getUserId());
        }

        System.out.println("🔒 Тикет " + ticketId + " закрыт: " + reason);
    }

    public void deleteTicket(String ticketId, String reason) {
        TicketDataExtended ticket = tickets.remove(ticketId);
        if (ticket == null) {
            return;
        }

        ConcurrentHashMap<Integer, TicketMessageLink> history = ticketMessageHistory.remove(ticketId);
        if (history != null) {
            for (Integer messageId : history.keySet()) {
                messageLinks.remove(messageId);
            }
        }

        userReplyToTicket.remove(ticket.getUserId());

        String deleteMessage = String.format(
                "🗑️ <b>Тикет #%s удален</b>\n" +
                        "👤 Пользователь: %s\n" +
                        "📝 Причина: %s\n",
                ticketId,
                ticket.getUsername(),
                reason
        );

        messageService.sendMessageHtml(DEVELOPER_ID, null, deleteMessage);

        System.out.println("🗑️ Тикет " + ticketId + " удален: " + reason);
    }

    // Закрытие тикета администратором
    public void closeTicketByAdmin(String ticketId, Long adminId, String reason) {
        TicketDataExtended ticket = tickets.get(ticketId);
        if (ticket == null) {
            return;
        }

        if (!ticket.isClosed()) {
            String closeReason = (reason == null || reason.isBlank()) ? "Закрыто агентом." : reason;
            closeTicket(ticketId, closeReason);


            messageService.sendMessageHtml(SUPPORT_CHAT_ID, SUPPORT_THREAD_ID,
                    "🔒 <b>Тикет #" + ticketId + " закрыт администратором</b>\n" +
                            "📝 <i>" + escapeHtml(closeReason) + "</i>\n" +
                            "👤 <i>Пользователь:</i> " + ticket.getUsername()
            );
        }
    }

    /**
     * Закрытие тикета по reply-сообщению поддержки с командой /close
     *   /close                - закрыть без указания причины
     *   /close причина...     - закрыть с текстом причины
     */
    public void closeTicketFromSupportReply(Integer replyToMessageId, Long supportUserId, String replyText) {
        closeTicketFromSupportReply(replyToMessageId, supportUserId, replyText, null);
    }

    public void closeTicketFromSupportReply(Integer replyToMessageId, Long supportUserId, String replyText, String replyToText) {
        TicketMessageLink link = resolveSupportMessageLink(replyToMessageId, replyToText);

        if (link == null) {
            messageService.sendMessageHtml(SUPPORT_CHAT_ID, SUPPORT_THREAD_ID,
                    "❌ <b>Не удалось найти тикет для этого сообщения.</b>\n" +
                            "Убедитесь, что вы отвечаете именно на сообщение с тикетом.");
            return;
        }

        String ticketId = link.getTicketId();

        String reason = "Закрыто агентом.";
        String[] parts = replyText.split(" ", 2);
        if (parts.length == 2 && !parts[1].isBlank()) {
            reason = parts[1].trim();
        }

        closeTicketByAdmin(ticketId, supportUserId, reason);
    }

    /**
     * Начать создание тикета
     */
    public void startTicketCreation(Long userId, String username) {
        boolean shadowTicket = isUserBanned(userId, username);

        for (TicketDataExtended ticket : tickets.values()) {
            if (ticket.getUserId().equals(userId) && !ticket.isClosed()) {
                messageService.sendMessageHtml(userId, null,
                        "⚠️ <b>У вас уже есть активный тикет #" + ticket.getTicketId() + "</b>\n\n" +
                                "Вы не можете создать новый тикет, пока не будет закрыт текущий.\n" +
                                "Закройте текущий тикет или дождитесь ответа поддержки.");
                return;
            }
        }
        TicketDataExtended shadowTicketData = shadowTicketsByUserId.get(userId);
        if (shadowTicketData != null && !shadowTicketData.isClosed()) {
            messageService.sendMessageHtml(userId, null,
                    "⚠️ <b>У вас уже есть активный тикет #" + shadowTicketData.getTicketId() + "</b>\n\n" +
                            "Вы не можете создать новый тикет, пока не будет закрыт текущий.\n" +
                            "Закройте текущий тикет или дождитесь ответа поддержки.");
            return;
        }

        if (shadowTicket) {
            shadowTicketCreationUserIds.add(userId);
        } else {
            shadowTicketCreationUserIds.remove(userId);
        }

        String ticketId = generateTicketId();
        userStates.put(userId, new TicketState(TicketStep.USERNAME, ticketId));

        String startMessage =
                "🧐 <b>Оу, у тебя появилась проблема или вопрос? Киворт поможет тебе правильно оформить обращение!</b>\n\n" +
                "👤 <b>Какой у тебя никнейм в игре?</b>";
        messageService.sendMessageHtml(userId, null, startMessage, CreateKeyboardUser.ticketCreationMenu());
    }

    /**
     * Обработка текстового ответа пользователя при создании тикета
     */
    public void handleUserResponse(Long userId, String text) {
        TicketState state = userStates.get(userId);
        if (state == null) return;

        switch (state.getCurrentStep()) {
            case USERNAME:
                state.setUsername(text);
                state.setCurrentStep(TicketStep.ISSUE_DESCRIPTION);
                messageService.sendMessageHtml(
                        userId,
                        null,
                        "🥳 <b>Отлично! А теперь перейдем к самой сути обращения.</b>\n\n" +
                                "✏️ Расскажи о том, что у тебя случилось? Постарайся описать проблему подробно, чтобы мои создатели смогли оказать качественную и оперативную помощь!",
                        CreateKeyboardUser.ticketCreationMenu()
                );
                break;

            case ISSUE_DESCRIPTION:
                state.setIssueDescription(text);
                state.setCurrentStep(TicketStep.EVIDENCE);
                messageService.sendMessageHtml(
                        userId,
                        null,
                        "🤔 <b>Да... Дело серьезное...</b>\n\n" +
                                "📎 Загрузи несколько скриншотов или видео, чтобы получить максимальную помощь! Моим создателям проще понять проблему наглядно!\n\n" +
                                "🖊 Напиши \"Нет\", если таковых не имеется.",
                        CreateKeyboardUser.ticketCreationMenu()
                );
                break;

            case EVIDENCE:
                if (text.equalsIgnoreCase("нет")) {
                    state.setEvidence("Не предоставлено");
                    state.setCurrentStep(TicketStep.CONFIRMATION);
                    sendTicketPreview(userId, state);
                } else {
                    messageService.sendMessageHtml(userId, null,
                            "❌ <b>Доказательства должны быть в виде фото или видео!</b>\n\n" +
                                    "Пожалуйста, отправьте:\n" +
                                    "• <b>Фото/видео файл</b> с доказательствами\n" +
                                    "• Или напишите <code>нет</code>, если доказательств нет\n\n" +
                                    "Текстовые описания не принимаются.");

                    state.setCurrentStep(TicketStep.EVIDENCE);
                }
                break;

            case CONFIRMATION:
                if ("✅ Отправить обращение".equals(text) || "да".equalsIgnoreCase(text)) {
                    finishTicketCreation(userId, state);
                    userStates.remove(userId);
                } else {
                    messageService.sendMessageHtml(
                            userId,
                            null,
                            "💠 <b>Почти готово!</b> Нажми <b>\"✅ Отправить обращение\"</b> или <b>\"❌ Отменить обращение\"</b>.",
                            CreateKeyboardUser.ticketConfirmMenu()
                    );
                }
                break;
        }
    }

    /**
     * Обработка медиафайла от пользователя при создании тикета
     */
    public void handleMedia(Long userId, String fileId, String caption, String mediaType) {
        TicketState state = userStates.get(userId);

        if (state == null) {
            if (isReplyingToTicket(userId)) {
                handleMediaReplyToTicket(userId, fileId, caption, mediaType);
                return;
            }

            messageService.sendMessage(userId, null,
                    "⚠️ Сначала начните создание тикета через меню (/start)");
            return;
        }

        if (state.getCurrentStep() == TicketStep.EVIDENCE) {
            state.setMediaFileId(fileId);
            state.setMediaType(mediaType);
            state.setMediaCaption(caption);

            state.setEvidence("Предоставлено");
            state.setCurrentStep(TicketStep.CONFIRMATION);
            sendTicketPreview(userId, state);
        }
    }

    /**
     * Обработка медиафайла в ответе на существующий тикет
     */
    private void handleMediaReplyToTicket(Long userId, String fileId, String caption, String mediaType) {
        String ticketId = userReplyToTicket.get(userId);

        if (ticketId == null) {
            messageService.sendMessage(userId, null,
                    "❌ У вас нет активных тикетов для ответа.\n" +
                            "Напишите /start для создания нового тикета.");
            return;
        }

        TicketDataExtended ticket = tickets.get(ticketId);
        if (ticket == null && isShadowTicket(userId, ticketId)) {
            ticket = shadowTicketsByUserId.get(userId);
        }
        if (ticket == null) {
            messageService.sendMessage(userId, null, "❌ Тикет не найден.");
            userReplyToTicket.remove(userId);
            return;
        }

        if (ticket.isClosed()) {
            messageService.sendMessageHtml(userId, null,
                    "❌ <b>Тикет #" + ticketId + " уже закрыт!</b>\n" +
                            "Вы не можете отправлять медиафайлы в закрытые тикеты.");
            userReplyToTicket.remove(userId);
            return;
        }

        if (ticket.isExpired()) {
            closeTicket(ticketId, "истек срок ожидания ответа (24 часа)");
            messageService.sendMessageHtml(userId, null,
                    "❌ <b>Тикет #" + ticketId + " уже закрыт!</b>\n" +
                            "Время для ответа истекло (24 часа).");
            userReplyToTicket.remove(userId);
            return;
        }

        pendingMediaReplies.put(userId, new UserMediaReply(ticketId, fileId, mediaType, caption));

        ticket.setLastUserReply(LocalDateTime.now());

        if (isShadowTicket(userId, ticketId)) {
            messageService.sendMessageHtml(userId, null, "✅ <b>Ответ успешно отправлен! Ожидаем...</b>");
            pendingMediaReplies.remove(userId);
            return;
        }

        sendMediaToSupportFromUser(userId, ticket, fileId, mediaType, caption);
    }

    /**
     * Отправка медиафайла от пользователя в тред поддержки
     */
    private void sendMediaToSupportFromUser(Long userId, TicketDataExtended ticket, String fileId, String mediaType, String caption) {
        String mediaCaption = String.format(
                "📎 <b>Медиа от пользователя по тикету #%s</b>\n\n" +
                        "👤 <b>Пользователь:</b> %s\n" +
                        "⏰ <b>Время:</b> %s\n" +
                        "%s\n\n" +
                        "<i>Ответьте reply'ем на это сообщение, чтобы продолжить диалог.</i>",
                ticket.getTicketId(),
                ticket.getUsername(),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")),
                caption != null ? "📝 <b>Подпись:</b> " + escapeHtml(caption) : ""
        );

        try {
            Integer supportMessageId = messageService.sendMediaToSupport(fileId, mediaType, mediaCaption);

            if (supportMessageId != null) {
                TicketMessageLink mediaLink = new TicketMessageLink(
                        userId,
                        ticket.getTicketId(),
                        ticket.getUsername(),
                        MessageType.MEDIA_MESSAGE
                );
                saveMessageToTicketHistory(supportMessageId, mediaLink);
            }

            messageService.sendMessageHtml(userId, null, "✅ <b>Ответ успешно отправлен! Ожидаем...</b>");

            pendingMediaReplies.remove(userId);

        } catch (Exception e) {
            e.printStackTrace();
            messageService.sendMessage(userId, null,
                    "❌ Ошибка при отправке медиафайла. Попробуйте позже.");
        }
    }

    public void cancelUserReply(Long userId) {
        String removedTicketId = userReplyToTicket.remove(userId);
        if (removedTicketId != null) {
            TicketDataExtended ticket = tickets.get(removedTicketId);
            if (ticket != null && !ticket.isClosed() && ticket.isExpired()) {
                closeTicket(removedTicketId, "истек срок ожидания ответа (24 часа)");
            }

            messageService.sendMessageHtml(userId, null,
                    "✅ <b>Ответ на тикет #" + removedTicketId + " отменен.</b>\n" +
                            "Вы можете создать новый тикет через /start");
        }
    }

    public void cancelTicketCreation(Long userId) {
        if (userStates.remove(userId) != null) {
            shadowTicketCreationUserIds.remove(userId);
            messageService.sendMessageHtml(
                    userId,
                    null,
                    "✅ <b>Обращение отменено.</b> Возвращаю тебя в главное меню!",
                    CreateKeyboardUser.mainMenu(userId, this)
            );
        }
    }

    public void startCloseTicketFlow(Long userId) {
        java.util.List<TicketDataExtended> activeTickets = new java.util.ArrayList<>();
        for (TicketDataExtended ticket : tickets.values()) {
            if (ticket.getUserId().equals(userId) && !ticket.isClosed()) {
                activeTickets.add(ticket);
            }
        }
        TicketDataExtended shadowTicket = shadowTicketsByUserId.get(userId);
        if (shadowTicket != null && !shadowTicket.isClosed()) {
            activeTickets.add(shadowTicket);
        }

        if (activeTickets.isEmpty()) {
            messageService.sendMessageHtml(userId, null, "❌ <b>У тебя нет активных обращений для закрытия.</b>");
            return;
        }

        activeTickets.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));

        StringBuilder text = new StringBuilder("🔒 <b>Выбери обращение для закрытия</b>\n\n");
        for (TicketDataExtended ticket : activeTickets) {
            text.append("• <code>").append(ticket.getTicketId()).append("</code> — ")
                    .append(ticket.getCreatedAt().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")))
                    .append("\n");
        }
        text.append("\nОтправь ID обращения (например: <code>")
                .append(activeTickets.get(0).getTicketId())
                .append("</code>)");

        pendingTicketCloseSelection.put(userId, true);
        messageService.sendMessageHtml(userId, null, text.toString(), CreateKeyboardUser.ticketCloseSelectionMenu());
    }

    public boolean isWaitingTicketCloseId(Long userId) {
        return pendingTicketCloseSelection.containsKey(userId);
    }

    public void cancelTicketCloseSelection(Long userId, boolean notify) {
        if (pendingTicketCloseSelection.remove(userId) != null && notify) {
            messageService.sendMessageHtml(userId, null, "✅ Выбор закрытия обращения отменен.");
        }
    }

    private TicketDataExtended getUserOwnedTicket(Long userId, String ticketId) {
        TicketDataExtended ticket = tickets.get(ticketId);
        if (ticket != null && ticket.getUserId().equals(userId)) {
            return ticket;
        }

        TicketDataExtended shadowTicket = shadowTicketsByUserId.get(userId);
        if (shadowTicket != null && shadowTicket.getTicketId().equals(ticketId)) {
            return shadowTicket;
        }

        ticket = getUserTicketHistoryFromDatabase(userId).stream()
                .filter(restored -> restored.getTicketId().equals(ticketId))
                .findFirst()
                .orElse(null);

        if (ticket != null) {
            tickets.putIfAbsent(ticketId, ticket);
        }

        return ticket;
    }

    private boolean isShadowTicket(Long userId, String ticketId) {
        TicketDataExtended shadowTicket = shadowTicketsByUserId.get(userId);
        return shadowTicket != null && shadowTicket.getTicketId().equals(ticketId);
    }

    public void handleTicketCloseIdInput(Long userId, String text) {
        if (!pendingTicketCloseSelection.containsKey(userId)) {
            return;
        }

        String ticketId = text.trim().toUpperCase();
        TicketDataExtended ticket = getUserOwnedTicket(userId, ticketId);
        if (ticket == null || ticket.isClosed()) {
            messageService.sendMessageHtml(userId, null,
                    "❌ <b>Обращение не найдено или уже закрыто.</b>\n" +
                            "Проверь ID и попробуй снова.");
            return;
        }

        pendingTicketCloseSelection.remove(userId);
        if (isShadowTicket(userId, ticketId)) {
            ticket.setClosed(true);
            userReplyToTicket.remove(userId);

            Integer userCloseMessageId = messageService.sendMessageHtmlWithResult(userId, null,
                    "🔒 <b>Ваше обращение #" + ticketId + " закрыто!</b>\n\n" +
                            "🔎 <b>Причина:</b> Закрыто пользователем.");
            rememberUserMessageLink(userId, userCloseMessageId, ticketId);
            return;
        }

        ticket.setClosed(true);
        userReplyToTicket.remove(userId);
        saveTicketHistory(ticket, "closed_by_user", "Закрыто пользователем.", null);

        messageService.sendMessageHtml(
                SUPPORT_CHAT_ID,
                SUPPORT_THREAD_ID,
                "🔒 <b>Обращение #" + ticketId + " закрыто пользователем.</b>\n" +
                        "👤 <b>Пользователь:</b> " + escapeHtml(ticket.getUsername())
        );

        Integer userCloseMessageId = messageService.sendMessageHtmlWithResult(userId, null,
                "🔒 <b>Ваше обращение #" + ticketId + " закрыто!</b>\n\n" +
                        "🔎 <b>Причина:</b> Закрыто пользователем.");
        rememberUserMessageLink(userId, userCloseMessageId, ticketId);
    }

    public void cancelTicketByUser(Long userId) {
        String activeTicketId = null;
        for (TicketDataExtended ticket : tickets.values()) {
            if (ticket.getUserId().equals(userId) && !ticket.isClosed()) {
                activeTicketId = ticket.getTicketId();
                break;
            }
        }
        TicketDataExtended shadowTicket = shadowTicketsByUserId.get(userId);
        if (activeTicketId == null && shadowTicket != null && !shadowTicket.isClosed()) {
            activeTicketId = shadowTicket.getTicketId();
        }

        if (activeTicketId != null) {
            messageService.sendMessageHtml(userId, null,
                    "❌ <b>Отмена тикета недоступна.</b>\n" +
                            "Дождитесь ответа поддержки. Тикет закроется автоматически через 24 часа после ответа поддержки.");
        } else {
            messageService.sendMessageHtml(userId, null,
                    "❌ <b>У вас нет активных тикетов для отмены.</b>");
        }
    }

    public boolean isReplyingToTicket(Long userId) {
        if (userReplyToTicket.containsKey(userId)) {
            return true;
        }

        TicketDataExtended shadowTicket = shadowTicketsByUserId.get(userId);
        if (shadowTicket != null && !shadowTicket.isClosed()) {
            userReplyToTicket.put(userId, shadowTicket.getTicketId());
            return true;
        }

        for (TicketDataExtended ticket : getUserTicketHistoryFromDatabase(userId)) {
            if (!ticket.isClosed()) {
                userReplyToTicket.put(userId, ticket.getTicketId());
                return true;
            }
        }

        return false;
    }

    private void sendTicketPreview(Long userId, TicketState state) {
        String preview = String.format(
                "💠 <b>Почти готово! Давай перепроверим, все ли верно указано!</b>\n\n" +
                        "📋 <b>Об обращении</b>\n" +
                        "• Никнейм: %s;\n" +
                        "• Текст обращения: %s;\n" +
                        "• Доказательства: %s.",
                escapeHtml(state.getUsername()),
                escapeHtml(state.getIssueDescription()),
                escapeHtml(state.getEvidence())
        );

        messageService.sendMessageHtml(userId, null, preview, CreateKeyboardUser.ticketConfirmMenu());
    }

    /**
     * Завершение создания тикета (с медиа или без)
     */
    private void finishTicketCreation(Long userId, TicketState state) {
        try {
            if (shadowTicketCreationUserIds.remove(userId)) {
                TicketDataExtended ticketData = new TicketDataExtended(
                        state.getTicketId(),
                        userId,
                        state.getUsername(),
                        state.getIssueDescription(),
                        state.getEvidence(),
                        LocalDateTime.now()
                );

                if (state.hasMedia()) {
                    ticketData.setMediaFileId(state.getMediaFileId());
                    ticketData.setMediaType(state.getMediaType());
                    ticketData.setMediaCaption(state.getMediaCaption());
                }

                shadowTicketsByUserId.put(userId, ticketData);

                String userMessage = String.format(
                        "✅ <b>Обращение успешно отправлено моим создателям! Ожидайте ответа...</b>\n\n" +
                                "📋 <b>Об обращении</b>\n" +
                                "• ID: %s;\n" +
                                "• Никнейм: %s;\n" +
                                "• Текст обращения: %s;\n" +
                                "• Доказательства: %s.\n\n" +
                                "⏰ Ответ поступит в течение 24 часов. По истечении этого срока, обращение автоматически закроется.\n\n" +
                                "❕ Если проблема уже решена, используйте соответствующую кнопку ниже.",
                        state.getTicketId(),
                        escapeHtml(state.getUsername()),
                        escapeHtml(state.getIssueDescription()),
                        escapeHtml(state.getEvidence())
                );

                Integer userMessageId = messageService.sendMessageHtmlWithResult(userId, null, userMessage, CreateKeyboardUser.linkedMainMenu());
                rememberUserMessageLink(userId, userMessageId, state.getTicketId());
                return;
            }

            TicketDataExtended ticketData = new TicketDataExtended(
                    state.getTicketId(),
                    userId,
                    state.getUsername(),
                    state.getIssueDescription(),
                    state.getEvidence(),
                    LocalDateTime.now()
            );

            if (state.hasMedia()) {
                ticketData.setMediaFileId(state.getMediaFileId());
                ticketData.setMediaType(state.getMediaType());
                ticketData.setMediaCaption(state.getMediaCaption());
            }

            tickets.put(state.getTicketId(), ticketData);
            saveTicketHistory(ticketData, "created", null, null);
            startCleanupScheduler();

            System.out.println("💾 Сохранен тикет в память: " + state.getTicketId());
            System.out.println("📊 Всего тикетов в памяти: " + tickets.size());

            // Отправляем тикет в поддержку (с медиа или без)
            Integer supportMessageId = sendTicketToSupport(state, ticketData);

            // Сохраняем связь messageId -> userId/ticketId
            if (supportMessageId != null) {
                TicketMessageLink initialLink = new TicketMessageLink(
                        userId,
                        state.getTicketId(),
                        state.getUsername(),
                        MessageType.INITIAL_TICKET
                );
                messageLinks.put(supportMessageId, initialLink);

                ConcurrentHashMap<Integer, TicketMessageLink> history = new ConcurrentHashMap<>();
                history.put(supportMessageId, initialLink);
                ticketMessageHistory.put(state.getTicketId(), history);

                System.out.println("🔗 Сохранена связь: сообщение " + supportMessageId + " -> тикет " + state.getTicketId() + " от " + state.getUsername());
            }

            sendTicketToDeveloper(state, ticketData);

            String userMessage = String.format(
                    "✅ <b>Обращение успешно отправлено моим создателям! Ожидайте ответа...</b>\n\n" +
                            "📋 <b>Об обращении</b>\n" +
                            "• ID: %s;\n" +
                            "• Никнейм: %s;\n" +
                            "• Текст обращения: %s;\n" +
                            "• Доказательства: %s.\n\n" +
                            "⏰ Ответ поступит в течение 24 часов. По истечении этого срока, обращение автоматически закроется.\n\n" +
                            "❕ Если проблема уже решена, используй соответствующую кнопку ниже.",
                    state.getTicketId(),
                    escapeHtml(state.getUsername()),
                    escapeHtml(state.getIssueDescription()),
                    escapeHtml(state.getEvidence())
            );

            Integer userMessageId = messageService.sendMessageHtmlWithResult(userId, null, userMessage, CreateKeyboardUser.linkedMainMenu());
            rememberUserMessageLink(userId, userMessageId, state.getTicketId());

        } catch (Exception e) {
            e.printStackTrace();
            messageService.sendMessageHtml(userId, null,
                    "❌ <b>Ошибка при создании тикета.</b>\n" +
                            "Попробуйте позже или свяжитесь с администратором.");
        }
    }

    /**
     * Отправка тикета в тред поддержки (с поддержкой медиа)
     */
    private Integer sendTicketToSupport(TicketState state, TicketDataExtended ticketData) {
        String caption = String.format(
                "🎫 <b>Новый тикет #%s</b>\n\n" +
                        "👤 <b>Никнейм:</b> %s\n" +
                        "📝 <b>Проблема:</b>\n%s\n" +
                        "📎 <b>Доказательства:</b> %s\n\n" +
                        "⏰ <b>Создан:</b> %s\n\n" +
                        "<i>Ответьте reply'ем на это сообщение, чтобы ответить пользователю.</i>",
                state.getTicketId(),
                escapeHtml(state.getUsername()),
                escapeHtml(state.getIssueDescription()),
                escapeHtml(state.getEvidence()),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
        );

        if (state.hasMedia()) {
            return messageService.sendMediaToSupport(
                    state.getMediaFileId(),
                    state.getMediaType(),
                    caption
            );
        } else {
            return messageService.sendMessageHtmlWithResult(SUPPORT_CHAT_ID, SUPPORT_THREAD_ID, caption);
        }
    }

    private void saveMessageToTicketHistory(Integer messageId, TicketMessageLink link) {
        messageLinks.put(messageId, link);

        ConcurrentHashMap<Integer, TicketMessageLink> history =
                ticketMessageHistory.getOrDefault(link.getTicketId(), new ConcurrentHashMap<>());
        history.put(messageId, link);
        ticketMessageHistory.put(link.getTicketId(), history);

        System.out.println("💾 Сохранено сообщение " + messageId + " в историю тикета " + link.getTicketId());
    }

    private TicketMessageLink resolveSupportMessageLink(Integer messageId, String replyToText) {
        TicketMessageLink link = messageId == null ? null : messageLinks.get(messageId);
        if (link != null) {
            return link;
        }

        String ticketId = extractTicketId(replyToText);
        if (ticketId == null) {
            return null;
        }

        TicketDataExtended ticket = tickets.get(ticketId);
        if (ticket == null) {
            for (TicketDataExtended candidate : tickets.values()) {
                if (ticketId.equals(candidate.getTicketId())) {
                    ticket = candidate;
                    break;
                }
            }
        }

        if (ticket == null) {
            return null;
        }

        link = new TicketMessageLink(
                ticket.getUserId(),
                ticket.getTicketId(),
                ticket.getUsername(),
                MessageType.SUPPORT_REPLY
        );

        if (messageId != null) {
            saveMessageToTicketHistory(messageId, link);
        }
        return link;
    }

    private String extractTicketId(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        Matcher matcher = Pattern
                .compile("(?i)#?(TKT-\\d{1,8})")
                .matcher(text);
        if (!matcher.find()) {
            return null;
        }

        return normalizeTicketId(matcher.group(1));
    }

    /**
     * Обработка ответа поддержки (с поддержкой медиа)
     */
    public void handleSupportReply(Integer replyToMessageId, Long supportUserId, String replyText) {
        handleSupportReply(replyToMessageId, supportUserId, replyText, null);
    }

    public void handleSupportReply(Integer replyToMessageId, Long supportUserId, String replyText, String replyToText) {
        TicketMessageLink link = resolveSupportMessageLink(replyToMessageId, replyToText);

        if (link == null) {
            System.out.println("⚠️ Не найдена связь для сообщения " + replyToMessageId);
            messageService.sendMessageHtml(SUPPORT_CHAT_ID, SUPPORT_THREAD_ID,
                    "❌ <b>Не удалось найти обращение для этого reply.</b>\n" +
                            "Ответьте на сообщение, где есть ID тикета вида <code>#TKT-0001</code>.");
            return;
        }

        Long targetUserId = link.getUserId();
        String ticketId = link.getTicketId();
        String username = link.getUsername();

        TicketDataExtended ticket = tickets.get(ticketId);
        if (ticket != null && ticket.isClosed()) {
            messageService.sendMessageHtml(SUPPORT_CHAT_ID, SUPPORT_THREAD_ID,
                    "❌ <b>Тикет #" + ticketId + " уже закрыт!</b>\n" +
                            "Вы не можете отвечать на закрытые тикеты.");
            return;
        }

        System.out.println("📨 Пересылка ответа: " + supportUserId + " -> " + targetUserId +
                " (тикет " + ticketId + ")");

        try {
            if (ticket != null) {
                ticket.setLastSupportReply(LocalDateTime.now());
                saveTicketHistory(ticket, "support_reply", replyText, supportUserId);
            }

            userReplyToTicket.put(targetUserId, ticketId);

            String userMessage = "🎩 <b>Киворт с новостями! Агент ответил на твое обращение! Смотри, что пишет.</b>\n\n" +
                    "👨‍💻 <b>Ответ агента:</b> " + escapeHtml(replyText) + "\n\n" +
                    "Ответь reply'ем на это сообщение, чтобы ответить агенту.";

            Integer userMessageId = messageService.sendMessageHtmlWithResult(targetUserId, null, userMessage);
            rememberUserMessageLink(targetUserId, userMessageId, ticketId);

            messageService.sendMessageHtml(SUPPORT_CHAT_ID, SUPPORT_THREAD_ID,
                    "✅ <b>Ответ переслан пользователю " + username + " (тикет #" + ticketId + ")</b>");

        } catch (Exception e) {
            e.printStackTrace();
            messageService.sendMessageHtml(SUPPORT_CHAT_ID, SUPPORT_THREAD_ID,
                    "❌ <b>Ошибка при пересылке ответа по тикету #" + ticketId + "</b>");
        }
    }

    /**
     * Обработка ответа поддержки с медиафайлом
     */
    public void handleSupportReplyWithMedia(Integer replyToMessageId, Long supportUserId,
                                            String fileId, String mediaType, String caption) {
        handleSupportReplyWithMedia(replyToMessageId, supportUserId, fileId, mediaType, caption, null);
    }

    public void handleSupportReplyWithMedia(Integer replyToMessageId, Long supportUserId,
                                            String fileId, String mediaType, String caption, String replyToText) {
        TicketMessageLink link = resolveSupportMessageLink(replyToMessageId, replyToText);

        if (link == null) {
            System.out.println("⚠️ Не найдена связь для сообщения " + replyToMessageId);
            messageService.sendMessageHtml(SUPPORT_CHAT_ID, SUPPORT_THREAD_ID,
                    "❌ <b>Не удалось найти обращение для этого reply.</b>\n" +
                            "Ответьте на сообщение, где есть ID тикета вида <code>#TKT-0001</code>.");
            return;
        }

        Long targetUserId = link.getUserId();
        String ticketId = link.getTicketId();
        String username = link.getUsername();

        TicketDataExtended ticket = tickets.get(ticketId);
        if (ticket != null && ticket.isClosed()) {
            messageService.sendMessageHtml(SUPPORT_CHAT_ID, SUPPORT_THREAD_ID,
                    "❌ <b>Тикет #" + ticketId + " уже закрыт!</b>\n" +
                            "Вы не можете отвечать на закрытые тикеты.");
            return;
        }

        try {
            if (ticket != null) {
                ticket.setLastSupportReply(LocalDateTime.now());
                saveTicketHistory(ticket, "support_media_reply", caption, supportUserId, mediaType, fileId, caption);
            }

            userReplyToTicket.put(targetUserId, ticketId);

            String userCaption = buildSupportMediaCaption(caption);

            Integer userMessageId = messageService.sendMediaToUserWithResult(targetUserId, fileId, mediaType, userCaption);
            rememberUserMessageLink(targetUserId, userMessageId, ticketId);

            messageService.sendMessageHtml(SUPPORT_CHAT_ID, SUPPORT_THREAD_ID,
                    "✅ <b>Медиа-ответ переслан пользователю " + username + " (тикет #" + ticketId + ")</b>");

        } catch (Exception e) {
            e.printStackTrace();
            messageService.sendMessageHtml(SUPPORT_CHAT_ID, SUPPORT_THREAD_ID,
                    "❌ <b>Ошибка при пересылке медиа-ответа по тикету #" + ticketId + "</b>");
        }
    }

    public void handleUserReplyToTicket(Long userId, String userReplyText) {
        handleUserReplyToTicket(userId, userReplyText, null, null);
    }

    private String buildSupportMediaCaption(String caption) {
        String header = "🎩 <b>Агент ответил на твое обращение и прикрепил файл.</b>\n\n";
        String footer = "\n\nОтветь reply'ем на это сообщение, чтобы ответить агенту.";
        if (caption == null || caption.isBlank()) {
            return header + footer.trim();
        }

        String safeCaption = caption.trim();
        int maxCaptionLength = 650;
        if (safeCaption.length() > maxCaptionLength) {
            safeCaption = safeCaption.substring(0, maxCaptionLength) + "...";
        }

        String text = "👨‍💻 <b>Ответ агента:</b> " + escapeHtml(safeCaption);
        return header + text + footer;
    }

    public void handleUserReplyToTicket(Long userId, String userReplyText, Integer replyToMessageId, Integer userMessageId) {
        String ticketId = resolveUserReplyTicketId(userId, replyToMessageId);

        if (ticketId == null) {
            messageService.sendMessage(userId, null,
                    "❌ У вас нет активных тикетов для ответа.\n" +
                            "Напишите /start для создания нового тикета.");
            return;
        }

        TicketDataExtended ticket = tickets.get(ticketId);
        if (ticket == null) {
            ticket = getUserOwnedTicket(userId, ticketId);
        }

        if (ticket == null) {
            messageService.sendMessage(userId, null, "❌ Тикет не найден.");
            userReplyToTicket.remove(userId);
            return;
        }

        if (ticket.isClosed()) {
            messageService.sendMessageHtml(userId, null,
                    "❌ <b>Тикет #" + ticketId + " уже закрыт!</b>\n" +
                            "Вы не можете отвечать на закрытые тикеты.");
            userReplyToTicket.remove(userId);
            return;
        }

        if (ticket.isExpired()) {
            closeTicket(ticketId, "истек срок ожидания ответа (24 часа)");
            messageService.sendMessageHtml(userId, null,
                    "❌ <b>Тикет #" + ticketId + " уже закрыт!</b>\n" +
                            "Время для ответа истекло (24 часа).");
            userReplyToTicket.remove(userId);
            return;
        }

        if (isShadowTicket(userId, ticketId)) {
            ticket.setLastUserReply(LocalDateTime.now());
            Integer confirmationId = messageService.sendMessageHtmlReplyToWithResult(
                    userId,
                    null,
                    userMessageId,
                    "✅ <b>Ответ успешно отправлен! Ожидаем...</b>"
            );
            if (confirmationId == null) {
                confirmationId = messageService.sendMessageHtmlWithResult(
                        userId,
                        null,
                        "✅ <b>Ответ успешно отправлен! Ожидаем...</b>"
                );
            }
            rememberUserMessageLink(userId, confirmationId, ticketId);
            return;
        }

        String replyMessage = String.format(
                "👤 <b>Ответ пользователя по тикету #%s</b>\n\n" +
                        "📝 <b>Сообщение:</b>\n%s\n\n" +
                        "⏰ <b>Время:</b> %s\n\n" +
                        "<i>Ответьте reply'ем на это сообщение, чтобы продолжить диалог.</i>",
                ticketId,
                escapeHtml(userReplyText),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
        );

        try {
            ticket.setLastUserReply(LocalDateTime.now());
            saveTicketHistory(ticket, "user_reply", userReplyText, userId);
            rememberUserMessageLink(userId, userMessageId, ticketId);

            Integer supportMessageId = messageService.sendMessageHtmlWithResult(
                    SUPPORT_CHAT_ID, SUPPORT_THREAD_ID, replyMessage
            );

            if (supportMessageId != null) {
                TicketMessageLink userReplyLink = new TicketMessageLink(
                        userId,
                        ticketId,
                        ticket.getUsername(),
                        MessageType.USER_REPLY
                );
                saveMessageToTicketHistory(supportMessageId, userReplyLink);
            }

            Integer confirmationId = messageService.sendMessageHtmlReplyToWithResult(
                    userId,
                    null,
                    userMessageId,
                    "✅ <b>Ответ успешно отправлен! Ожидаем...</b>"
            );
            if (confirmationId == null) {
                confirmationId = messageService.sendMessageHtmlWithResult(
                        userId,
                        null,
                        "✅ <b>Ответ успешно отправлен! Ожидаем...</b>"
                );
            }
            rememberUserMessageLink(userId, confirmationId, ticketId);

        } catch (Exception e) {
            e.printStackTrace();
            messageService.sendMessage(userId, null,
                    "❌ Ошибка при отправке ответа. Попробуйте позже.");
        }
    }

    private void sendTicketToDeveloper(TicketState state, TicketDataExtended ticketData) {
        String message = String.format(
                "🔔 <b>Дубликат тикета #%s</b>\n\n" +
                        "👤 <b>Никнейм:</b> %s\n" +
                        "📝 <b>Проблема:</b>\n%s\n" +
                        "📎 <b>Доказательства:</b> %s\n\n" +
                        "⏰ <b>Создан:</b> %s",
                state.getTicketId(),
                state.getUsername(),
                state.getIssueDescription(),
                state.getEvidence(),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
        );

        if (state.hasMedia()) {
            String mediaCaption = String.format(
                    "🔔 <b>Дубликат тикета #%s</b>\n\n" +
                            "👤 <b>Никнейм:</b> %s\n" +
                            "📝 <b>Проблема:</b>\n%s\n" +
                            "📎 <b>Доказательства:</b> %s\n\n" +
                            "⏰ <b>Создан:</b> %s",
                    state.getTicketId(),
                    state.getUsername(),
                    state.getIssueDescription(),
                    state.getEvidence(),
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
            );

            messageService.sendMediaToUser(DEVELOPER_ID, state.getMediaFileId(),
                    state.getMediaType(), mediaCaption);
        } else {
            messageService.sendMessageHtml(DEVELOPER_ID, null, message);
        }
    }

    private String escapeHtml(String text) {
        return MessageService.escapeHtml(text);
    }

    private void saveTicketHistory(TicketDataExtended ticket, String eventType, String message, Long actorId) {
        saveTicketHistory(ticket, eventType, message, actorId, null, null, null);
    }

    private void saveTicketHistory(TicketDataExtended ticket, String eventType, String message, Long actorId,
                                   String mediaType, String mediaFileId, String mediaCaption) {
        JsonObject entry = new JsonObject();
        entry.addProperty("ticketId", ticket.getTicketId());
        entry.addProperty("event", eventType);
        entry.addProperty("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        entry.addProperty("username", ticket.getUsername());
        entry.addProperty("issueDescription", ticket.getIssueDescription());
        entry.addProperty("evidence", ticket.getEvidence());
        entry.addProperty("closed", ticket.isClosed());

        if (actorId != null) {
            entry.addProperty("actorId", actorId);
        }
        if (message != null && !message.isBlank()) {
            entry.addProperty("message", message);
        }
        String savedMediaType = mediaType == null ? ticket.getMediaType() : mediaType;
        String savedMediaFileId = mediaFileId == null ? ticket.getMediaFileId() : mediaFileId;
        String savedMediaCaption = mediaCaption == null ? ticket.getMediaCaption() : mediaCaption;
        if (savedMediaFileId != null && !savedMediaFileId.isBlank()) {
            entry.addProperty("mediaType", savedMediaType);
            entry.addProperty("mediaFileId", savedMediaFileId);
            if (savedMediaCaption != null) {
                entry.addProperty("mediaCaption", savedMediaCaption);
            }
        }

        SQL.appendTicketHistory(ticket.getUserId(), entry);
    }

    private void rememberUserMessageLink(Long userId, Integer messageId, String ticketId) {
        if (userId == null || messageId == null || ticketId == null) {
            return;
        }

        userPrivateMessageLinks
                .computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>())
                .put(messageId, ticketId);
        TicketDataExtended ticket = tickets.get(ticketId);
        if (ticket == null && isShadowTicket(userId, ticketId)) {
            ticket = shadowTicketsByUserId.get(userId);
        }
        if (ticket == null || !ticket.isClosed()) {
            userReplyToTicket.put(userId, ticketId);
        }
    }

    private String resolveUserReplyTicketId(Long userId, Integer replyToMessageId) {
        if (replyToMessageId != null) {
            ConcurrentHashMap<Integer, String> links = userPrivateMessageLinks.get(userId);
            if (links != null) {
                String linkedTicketId = links.get(replyToMessageId);
                if (linkedTicketId != null) {
                    return linkedTicketId;
                }
            }
        }

        return userReplyToTicket.get(userId);
    }

    private java.util.List<TicketDataExtended> getUserTicketHistoryFromDatabase(Long userId) {
        java.util.List<TicketDataExtended> restoredTickets = restoreTicketsFromHistory(userId, SQL.getTicketHistory(userId));
        for (TicketDataExtended ticket : restoredTickets) {
            restoreTicketToMemory(ticket);
        }
        return restoredTickets;
    }

    private java.util.List<TicketDataExtended> restoreTicketsFromHistory(Long userId, JsonArray history) {
        Map<String, TicketDataExtended> restoredTickets = new java.util.HashMap<>();

        for (JsonElement element : history) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject entry = element.getAsJsonObject();
            String ticketId = getJsonString(entry, "ticketId", "ticket_id", "id");
            ticketId = normalizeTicketId(ticketId);
            if (ticketId == null) {
                continue;
            }

            TicketDataExtended existing = restoredTickets.get(ticketId);
            if (existing != null) {
                mergeTicketState(existing, entry);
                continue;
            }

            String username = getJsonString(entry, "username", "playerName", "nickname");
            String issueDescription = getJsonString(entry, "issueDescription", "description", "text", "message");
            String evidence = getJsonString(entry, "evidence", "proof");
            String timestamp = getJsonString(entry, "timestamp", "createdAt", "created_at");

            LocalDateTime createdAt;
            try {
                createdAt = timestamp == null ? LocalDateTime.now() : LocalDateTime.parse(timestamp);
            } catch (Exception ignored) {
                createdAt = LocalDateTime.now();
            }

            TicketDataExtended ticket = new TicketDataExtended(
                    ticketId,
                    userId,
                    username == null ? "Unknown" : username,
                    issueDescription == null ? "" : issueDescription,
                    evidence == null ? "" : evidence,
                    createdAt
            );
            ticket.setClosed(isTicketClosedInHistory(history, ticketId));
            mergeTicketState(ticket, entry);
            restoredTickets.put(ticketId, ticket);
        }

        java.util.List<TicketDataExtended> restored = new java.util.ArrayList<>(restoredTickets.values());
        restored.sort((t1, t2) -> t2.getCreatedAt().compareTo(t1.getCreatedAt()));
        return restored;
    }

    private String normalizeTicketId(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            return null;
        }

        String normalized = ticketId.trim().toUpperCase();
        if (normalized.matches("\\d+")) {
            try {
                return "TKT-" + String.format("%04d", Integer.parseInt(normalized));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return normalized;
    }

    private void mergeTicketState(TicketDataExtended ticket, JsonObject entry) {
        if (ticket == null || entry == null) {
            return;
        }

        String event = getJsonString(entry, "event");
        String timestamp = getJsonString(entry, "timestamp", "createdAt", "created_at");
        LocalDateTime eventTime = parseDateTimeOrNull(timestamp);

        if ("support_reply".equals(event) || "support_media_reply".equals(event)) {
            ticket.setLastSupportReply(eventTime == null ? LocalDateTime.now() : eventTime);
        } else if ("user_reply".equals(event)) {
            ticket.setLastUserReply(eventTime == null ? LocalDateTime.now() : eventTime);
        }

        String mediaFileId = getJsonString(entry, "mediaFileId", "fileId");
        if (mediaFileId != null) {
            ticket.setMediaFileId(mediaFileId);
            ticket.setMediaType(getJsonString(entry, "mediaType", "type"));
            ticket.setMediaCaption(getJsonString(entry, "mediaCaption", "caption"));
        }
    }

    private LocalDateTime parseDateTimeOrNull(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }

        try {
            return LocalDateTime.parse(timestamp);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isTicketClosedInHistory(JsonArray history, String ticketId) {
        for (JsonElement element : history) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject entry = element.getAsJsonObject();
            if (!ticketId.equals(normalizeTicketId(getJsonString(entry, "ticketId", "ticket_id", "id")))) {
                continue;
            }

            String event = getJsonString(entry, "event");
            if ("closed".equals(event) || "closed_by_user".equals(event) || "closed_by_support".equals(event)) {
                return true;
            }

            JsonElement closed = entry.get("closed");
            if (closed != null && !closed.isJsonNull() && closed.getAsBoolean()) {
                return true;
            }
        }

        return false;
    }

    private String getJsonString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private String getJsonString(JsonObject object, String... keys) {
        for (String key : keys) {
            String value = getJsonString(object, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String generateTicketId() {
        String ticketId = "TKT-" + String.format("%04d", ticketCounter++);
        return ticketId;
    }

    // Методы для отладки
    public int getTicketCount() {
        return tickets.size();
    }

    public int getActiveTicketCount() {
        return (int) tickets.values().stream()
                .filter(ticket -> !ticket.isClosed())
                .count();
    }

    public int getMessageLinksCount() {
        return messageLinks.size();
    }

    /**
     * Информация о тикете
     */
    public void getTicketInfo(String ticketId, Long requesterId) {
        TicketDataExtended ticket = tickets.get(ticketId);
        if (ticket == null) {
            messageService.sendMessageHtml(requesterId, null,
                    "❌ <b>Тикет #" + ticketId + " не найден.</b>");
            return;
        }

        String status = ticket.isClosed() ? "🔒 ЗАКРЫТ" : "🟢 АКТИВЕН";
        String lastSupportReply = ticket.getLastSupportReply() != null ?
                ticket.getLastSupportReply().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) : "нет ответов";

        String expiryInfo = "";
        if (!ticket.isClosed() && ticket.getLastSupportReply() != null) {
            LocalDateTime expiryTime = ticket.getExpiryTime();
            if (expiryTime != null) {
                long hoursLeft = ChronoUnit.HOURS.between(LocalDateTime.now(), expiryTime);
                if (hoursLeft > 0) {
                    expiryInfo = "\n⏳ <b>Закроется через:</b> " + hoursLeft + " часов";
                } else {
                    expiryInfo = "\n⚠️ <b>Срок истекает:</b> скоро";
                }
            }
        }

        String info = String.format(
                "📋 <b>Информация о тикете #%s</b>\n\n" +
                        "👤 <b>Пользователь:</b> %s\n" +
                        "📅 <b>Создан:</b> %s\n" +
                        "📅 <b>Последний ответ поддержки:</b> %s\n" +
                        "📊 <b>Статус:</b> %s\n" +
                        "%s\n\n" +
                        "📝 <b>Проблема:</b>\n%s",
                ticketId,
                ticket.getUsername(),
                ticket.getCreatedAt().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")),
                lastSupportReply,
                status,
                expiryInfo,
                escapeHtml(ticket.getIssueDescription())
        );

        messageService.sendMessageHtml(requesterId, null, info);
    }


    public void listTickets() {
        System.out.println("📋 Список тикетов в памяти:");
        tickets.forEach((id, ticket) -> {
            String status = ticket.isClosed() ? "ЗАКРЫТ" : "АКТИВЕН";
            System.out.println("• " + id + " - " + ticket.getUsername() +
                    " (" + status + ", создан: " + ticket.getCreatedAt() + ")");
        });
    }

    public void showTicketHistory(String ticketId) {
        ConcurrentHashMap<Integer, TicketMessageLink> history = ticketMessageHistory.get(ticketId);
        if (history == null) {
            System.out.println("❌ История сообщений для тикета " + ticketId + " не найдена");
            return;
        }

        System.out.println("📜 История сообщений тикета " + ticketId + ":");
        for (Map.Entry<Integer, TicketMessageLink> entry : history.entrySet()) {
            System.out.println("  • Message ID: " + entry.getKey() +
                    ", Тип: " + entry.getValue().getMessageType() +
                    ", Пользователь: " + entry.getValue().getUsername());
        }
    }

    public boolean hasOpenTicket(Long userId) {
        TicketDataExtended shadowTicket = shadowTicketsByUserId.get(userId);
        if (shadowTicket != null && !shadowTicket.isClosed()) {
            return true;
        }

        for (TicketDataExtended ticket : tickets.values()) {
            if (ticket.getUserId().equals(userId) && !ticket.isClosed()) {
                return true;
            }
        }
        return false;
    }

    public TicketDataExtended getOpenTicket(Long userId) {
        TicketDataExtended shadowTicket = shadowTicketsByUserId.get(userId);
        if (shadowTicket != null && !shadowTicket.isClosed()) {
            return shadowTicket;
        }

        for (TicketDataExtended ticket : tickets.values()) {
            if (ticket.getUserId().equals(userId) && !ticket.isClosed()) {
                return ticket;
            }
        }
        return null;
    }

    public java.util.List<TicketDataExtended> getUserTicketHistory(Long userId) {
        java.util.List<TicketDataExtended> userTickets = new java.util.ArrayList<>();
        TicketDataExtended shadowTicket = shadowTicketsByUserId.get(userId);
        if (shadowTicket != null) {
            userTickets.add(shadowTicket);
        }

        for (TicketDataExtended ticket : tickets.values()) {
            if (ticket.getUserId().equals(userId)) {
                userTickets.add(ticket);
            }
        }
        userTickets.sort((t1, t2) -> t2.getCreatedAt().compareTo(t1.getCreatedAt()));
        return userTickets;
    }

    public void showUserTicketHistory(Long userId) {
        java.util.List<TicketDataExtended> userTickets = getUserTicketHistory(userId);
        if (userTickets.isEmpty()) {
            userTickets = getUserTicketHistoryFromDatabase(userId);
        }

        if (userTickets.isEmpty()) {
            messageService.sendMessageHtml(userId, null,
                    "📂 <b>Ваши обращения</b>\n\n" +
                            "У вас пока нет созданных обращений.\n" +
                            "Создайте первое обращение через кнопку \"📝 Создать обращение\"");
            return;
        }

        StringBuilder historyText = new StringBuilder();
        historyText.append("📂 <b>Ваши обращения</b>\n\n");

        int activeCount = 0;
        int closedCount = 0;

        for (TicketDataExtended ticket : userTickets) {
            String status = ticket.isClosed() ? "🔒 ЗАКРЫТ" : "🟢 АКТИВЕН";
            String statusEmoji = ticket.isClosed() ? "🔒" : "🟢";

            if (!ticket.isClosed()) {
                activeCount++;
            } else {
                closedCount++;
            }

            String description = ticket.getIssueDescription();
            if (description.length() > 50) {
                description = description.substring(0, 50) + "...";
            }

            historyText.append(String.format(
                    "%s <b>#%s</b> - %s\n" +
                            "   📅 %s\n" +
                            "   📝 %s\n\n",
                    statusEmoji,
                    ticket.getTicketId(),
                    status,
                    ticket.getCreatedAt().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")),
                    escapeHtml(description)
            ));
        }

        historyText.append(String.format(
                "\n📊 <b>Статистика:</b>\n" +
                        "🟢 Активных: %d\n" +
                        "🔒 Закрытых: %d\n" +
                        "📋 Всего: %d\n\n" +
                        "Чтобы закрыть обращение, нажмите кнопку <b>\"🔒 Закрыть обращение\"</b>.",
                activeCount,
                closedCount,
                userTickets.size()
        ));

        messageService.sendMessageHtml(userId, null, historyText.toString(), CreateKeyboardUser.ticketsMenu());
    }

    public boolean isCreatingTicket(Long userId) {
        return userStates.containsKey(userId);
    }

    public void shutdown() {
        cleanupScheduler.shutdown();
        try {
            if (!cleanupScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupScheduler.shutdownNow();
        }
        System.out.println("🛑 TicketService остановлен");
    }
}

