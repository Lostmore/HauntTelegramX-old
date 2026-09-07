package haunt.trust.handlers;

import haunt.trust.NettyServerMonitor;
import haunt.trust.commands.*;
import haunt.trust.git.Github;
import haunt.trust.git.Gitlab;
import haunt.trust.tickets.TicketService;
import haunt.trust.utils.MessageService;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

public final class AdminUpdateHandler {
    private static final long STAFF_CHAT_ID = -1002521750905L;
    private static final long SERVER_PANEL_TTL_SECONDS = 120;

    private final TelegramClient telegram;
    private final MessageService messages;
    private final TicketService tickets;
    private final AccessPolicy access;
    private final ClearCivCraftUsersCommand clearUsers;
    private final HelpCommand help;
    private final AddAdminCommand addAdmin;
    private final CmdCommand commands;
    private final StateCommand state;
    private final InlineKeyboardCommand panel;
    private final PingCommand ping;
    private final GitlabStatusCommand gitlab;
    private final GithubStatusCommand github;
    private final DebugCommand debug;
    private final ActionLogCommand actions;
    private final AuthTelegramCommand auth;
    private final SettingsCommand settings;
    private boolean waitingForCommand;
    private Integer commandThreadId;
    private final ConcurrentHashMap<String, Long> usedServerConfirmations = new ConcurrentHashMap<>();

    public AdminUpdateHandler(TelegramClient telegram, NettyServerMonitor monitor, MessageService messages,
                              TicketService tickets, AccessPolicy access, ClearCivCraftUsersCommand clearUsers) {
        this.telegram = telegram;
        this.messages = messages;
        this.tickets = tickets;
        this.access = access;
        this.clearUsers = clearUsers;
        this.help = new HelpCommand(messages);
        this.addAdmin = new AddAdminCommand(telegram, messages);
        this.commands = new CmdCommand(telegram, messages, monitor);
        this.state = new StateCommand(messages);
        this.panel = new InlineKeyboardCommand(telegram, monitor, messages);
        this.ping = new PingCommand(telegram, messages, monitor);
        this.gitlab = new GitlabStatusCommand(new Gitlab(messages), messages);
        this.github = new GithubStatusCommand(new Github(messages), messages);
        this.debug = new DebugCommand(messages);
        this.actions = new ActionLogCommand(messages);
        this.auth = new AuthTelegramCommand(messages);
        this.settings = new SettingsCommand(messages, telegram);
    }

    public void handleMessage(Message message) {
        String text = message.getText();
        if (text == null) return;
        Long chatId = message.getChatId();
        Integer threadId = message.getMessageThreadId();
        Long userId = message.getFrom().getId();

        if (text.startsWith("/unlink")) {
            auth.handleUnlinkCommand(message, chatId, threadId);
            return;
        }
        if (!access.isAdmin(userId) && !access.isSupport(userId)) return;

        if (text.startsWith("/debug")) {
            if (!requireDeveloper(userId, chatId, threadId)) return;
            if (text.startsWith("/debug_raw")) debug.handleRawDebugCommand(message, chatId, threadId);
            else debug.handleDebugCommand(message, chatId, threadId);
        } else if (text.startsWith("/action")) {
            if (access.isAdmin(userId)) actions.handleActionCommand(message, chatId, threadId, text);
        }  else if (text.equalsIgnoreCase("/clearCivCraftUsers")) {
            if (access.isAdmin(userId)) clearUsers.handle(chatId, threadId);
        } else if (text.startsWith("/gitlab_")) {
            if (requireDeveloper(userId, chatId, threadId)) gitlab.handleGitlabStatusCommand(text, chatId, threadId);
        } else if (text.startsWith("/github_")) {
            if (requireDeveloper(userId, chatId, threadId)) github.handleGithubStatusCommand(text, chatId, threadId);
        } else if (waitingForCommand && chatId.equals(STAFF_CHAT_ID) && threadId != null && threadId.equals(commandThreadId)) {
            if (!access.isAdmin(userId)) return;
            commands.handleServerCommand(text, chatId, threadId);
            waitingForCommand = false;
            commandThreadId = null;
        } else if (text.startsWith("/addadmin")) {
            if (access.isAdmin(userId)) addAdmin.handleAddAdminCommand(text, chatId, threadId);
        } else if (text.startsWith("/addsupport")) {
            if (access.isAdmin(userId)) addAdmin.handleAddSupportCommand(text, chatId, threadId);
        } else if (text.startsWith("/ticket_close")) {
            closeTicket(text, chatId, threadId, userId);
        } else if (text.startsWith("/ban") || text.startsWith("/banned")) {
            changeBan(text, chatId, threadId, userId, true);
        } else if (text.startsWith("/unban")) {
            changeBan(text, chatId, threadId, userId, false);
        } else if (text.startsWith("/help")) {
            showHelp(chatId, threadId);
        } else if (text.startsWith("/state")) {
            state.handleStateCommand(text, chatId, threadId);
        } else if (text.startsWith("/ping")) {
            ping.handlePingCommand(chatId, threadId);
        } else if (text.startsWith("/cmd")) {
            if (access.isAdmin(userId)) commands.handleCmdCommand(text, chatId, threadId);
        } else if (text.startsWith("/test")) {
            if (chatId == 1137489984L) new TestCommand(messages).handleTestCommand(chatId, threadId);
            else messages.sendMessage(chatId, threadId, "У вас нет прав для выполнения этой команды.");
        } else if (text.equalsIgnoreCase("/panel")) {
            if (access.isAdmin(userId)) panel.handlePanelCommand(chatId, threadId);
        } else if (text.startsWith("/stop")) {
            if (access.isAdmin(userId)) sendServerConfirmation(chatId, threadId, true);
        } else if (text.startsWith("/restart") && access.isAdmin(userId)) {
            sendServerConfirmation(chatId, threadId, false);
        }
    }

    public void handleCallback(Update update) {
        CallbackQuery callback = update.getCallbackQuery();
        String data = callback.getData();
        Long userId = callback.getFrom().getId();
        if (!access.isAdmin(userId)) {
            answer(callback, "❌ У вас нет прав для этого действия", true);
            return;
        }
        if (data != null && (data.startsWith("act:") || data.startsWith("actnoop:"))) {
            actions.handleCallbackQuery(callback);
            return;
        }
        if (data != null && data.startsWith("settings_toggle_")) {
            if (!isAccountLinked(userId)) {
                answer(callback, "❌ Вы не авторизованы!", true);
                return;
            }
            Message message = (Message) callback.getMessage();
            settings.handleCallback(message.getChatId(), message.getMessageId(), data);
            return;
        }
        if (isServerPanelCallback(data) && serverPanelCallbackExpired(callback)) return;
        if ("server_cmd".equals(data)) {
            if (callback.getMessage() instanceof Message message) {
                waitingForCommand = true;
                commandThreadId = message.getMessageThreadId();
                messages.sendMessage(message.getChatId(), commandThreadId,
                        "⌨️ Введите команду для сервера (например: say Привет)");
                answer(callback, "Ожидаю вашу команду...", false);
            }
            return;
        }
        if (isServerConfirmation(data) && !consumeServerConfirmation(callback)) return;
        panel.handleCallbackQuery(update);
    }

    private boolean consumeServerConfirmation(CallbackQuery callback) {
        if (!(callback.getMessage() instanceof Message message)) {
            answer(callback, "Подтверждение недоступно.", true);
            return false;
        }

        long now = System.currentTimeMillis() / 1000;
        if (serverPanelCallbackExpired(message.getDate(), now)) {
            answer(callback, "Время подтверждения истекло. Выполните команду заново.", true);
            return false;
        }

        usedServerConfirmations.entrySet().removeIf(entry -> now - entry.getValue() > SERVER_PANEL_TTL_SECONDS);
        String key = message.getChatId() + ":" + message.getMessageId();
        if (usedServerConfirmations.putIfAbsent(key, now) != null) {
            answer(callback, "Это подтверждение уже использовано.", true);
            return false;
        }
        return true;
    }

    private boolean serverPanelCallbackExpired(CallbackQuery callback) {
        if (callback.getMessage() instanceof Message message
                && !serverPanelCallbackExpired(message.getDate(), System.currentTimeMillis() / 1000)) {
            return false;
        }
        answer(callback, "Кнопки устарели. Откройте панель или выполните команду заново.", true);
        return true;
    }

    static boolean serverPanelCallbackExpired(Integer messageDate, long now) {
        return messageDate == null || now - messageDate > SERVER_PANEL_TTL_SECONDS;
    }

    private boolean isServerConfirmation(String data) {
        return "restart_true".equals(data) || "restart_false".equals(data)
                || "stop_confirm".equals(data) || "cancel_action".equals(data);
    }

    private boolean isServerPanelCallback(String data) {
        return "manage".equals(data) || "info".equals(data) || "server_cmd".equals(data)
                || isServerConfirmation(data);
    }

    private void closeTicket(String text, Long chatId, Integer threadId, Long userId) {
        if (!access.canManageTickets(userId)) return;
        String[] parts = text.split(" ", 3);
        if (parts.length == 3) tickets.closeTicketByAdmin(parts[1], userId, parts[2]);
        else messages.sendMessageHtml(chatId, threadId,
                "❌ <b>Использование:</b> /ticket_close TKT-0001 причина закрытия");
    }

    private void changeBan(String text, Long chatId, Integer threadId, Long userId, boolean ban) {
        if (!access.canManageTickets(userId)) {
            messages.sendMessageHtml(chatId, threadId, "❌ <b>У вас нет прав для использования этой команды.</b>");
            return;
        }
        String[] parts = text.split(" ", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            messages.sendMessageHtml(chatId, threadId,
                    "❌ <b>Использование:</b> /" + (ban ? "ban" : "unban") + " &lt;telegram_id | @username | username&gt;");
            return;
        }
        messages.sendMessageHtml(chatId, threadId,
                ban ? tickets.banUser(parts[1].trim()) : tickets.unbanUser(parts[1].trim()));
    }

    private void showHelp(Long chatId, Integer threadId) {
        if (!chatId.equals(STAFF_CHAT_ID)) return;
        if (threadId != null && threadId == 8) help.handleHelpCommandTopic8(chatId, threadId);
        else if (threadId == null || threadId == 0) help.handleHelpCommandMainChat(chatId, threadId);
        else messages.sendMessageHtml(chatId, threadId,
                    "❌ Команда /help доступна только в <a href=\"https://t.me/c/2521750905/8\">топике 8</a> или <a href=\"https://t.me/c/2521750905/1\">основном чате</a>.");
    }

    private void sendServerConfirmation(Long chatId, Integer threadId, boolean stop) {
        if (!chatId.equals(STAFF_CHAT_ID) || threadId == null || (threadId != 5 && threadId != 6 && threadId != 7)) return;
        InlineKeyboardMarkup keyboard = stop ? panel.createStopConfirmKeyboard() : panel.createRestartConfirmKeyboard();
        try {
            telegram.execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .messageThreadId(threadId)
                    .text(stop ? "⚠️ Вы уверены, что хотите ОСТАНОВИТЬ сервер?" : "⚠️ Вы уверены, что хотите перезагрузить сервер?")
                    .replyMarkup(keyboard)
                    .build());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private boolean requireDeveloper(Long userId, Long chatId, Integer threadId) {
        if (access.isDeveloper(userId)) return true;
        messages.sendMessageHtml(chatId, threadId, "❌ Команда доступна только разработчику.");
        return false;
    }

    private void answer(CallbackQuery callback, String text, boolean alert) {
        try {
            telegram.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callback.getId()).text(text).showAlert(alert).build());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private boolean isAccountLinked(Long userId) {
        try {
            String response = NettyServerMonitor.sendAuthCommand("auth_check " + userId).get(5, TimeUnit.SECONDS);
            return response != null && response.startsWith("LINKED:");
        } catch (Exception ignored) {
            return false;
        }
    }
}
