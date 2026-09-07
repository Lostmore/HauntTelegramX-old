package haunt.trust.handlers;

import haunt.trust.NettyServerMonitor;
import haunt.trust.commands.AuthTelegramCommand;
import haunt.trust.commands.ClearCivCraftUsersCommand;
import haunt.trust.commands.CreateKeyboardUser;
import haunt.trust.commands.PlayerCommands;
import haunt.trust.commands.SettingsCommand;
import haunt.trust.tickets.TicketService;
import haunt.trust.utils.MessageService;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class PrivateMessageHandler {
    private static final Set<String> MENU_ACTIONS = Set.of(
            "📝 Создать обращение", "🪪 Привязка аккаунта", "🖥 Главное меню",
            "❌ Отменить привязку", "Помощь", "📜 История тикетов",
            "📂 Ваши обращения", "🔒 Закрыть обращение"
    );

    private final MessageService messages;
    private final TicketService tickets;
    private final AccessPolicy access;
    private final PlayerCommands players;
    private final AuthTelegramCommand auth;
    private final SettingsCommand settings;
    private final ClearCivCraftUsersCommand clearUsers;

    public PrivateMessageHandler(TelegramClient telegram, MessageService messages, TicketService tickets,
                                 AccessPolicy access, ClearCivCraftUsersCommand clearUsers) {
        this.messages = messages;
        this.tickets = tickets;
        this.access = access;
        this.clearUsers = clearUsers;
        this.players = new PlayerCommands(messages);
        this.auth = new AuthTelegramCommand(messages);
        this.settings = new SettingsCommand(messages, telegram);
    }

    public void handle(Message message) {
        String text = message.getText();
        if (text == null) return;

        Long chatId = message.getChatId();
        Long userId = message.getFrom().getId();
        if (text.equalsIgnoreCase("/clearCivCraftUsers") && access.isAdmin(userId)) {
            clearUsers.handle(chatId, null);
            return;
        }
        if ("❎ Отменить закрытие".equals(text)) {
            tickets.cancelTicketCloseSelection(userId, true);
            return;
        }
        if ("❌ Отменить обращение".equals(text)) {
            tickets.cancelTicketCreation(userId);
            return;
        }
        if ("✅ Отправить обращение".equals(text) && tickets.isCreatingTicket(userId)) {
            tickets.handleUserResponse(userId, text);
            return;
        }
        if (tickets.isWaitingTicketCloseId(userId)) {
            if (Set.of("🖥 Главное меню", "📂 Ваши обращения", "📝 Создать обращение",
                    "🪪 Привязка аккаунта", "Помощь", "/start").contains(text)) {
                tickets.cancelTicketCloseSelection(userId, false);
            } else {
                tickets.handleTicketCloseIdInput(userId, text);
                return;
            }
        }
        if (text.equals("/help") || text.startsWith("/help@") || text.equals("Помощь")) {
            sendPlayerHelp(chatId);
            return;
        }
        if (tickets.isCreatingTicket(userId)) {
            tickets.handleUserResponse(userId, text);
            return;
        }
        if (!text.startsWith("/") && !MENU_ACTIONS.contains(text)) {
            if (tickets.isReplyingToTicket(userId)) {
                Integer replyId = message.getReplyToMessage() == null ? null : message.getReplyToMessage().getMessageId();
                tickets.handleUserReplyToTicket(userId, text, replyId, message.getMessageId());
            } else {
                sendFallback(chatId, userId);
            }
            return;
        }
        if (text.startsWith("/start")) {
            handleStart(message, text);
            return;
        }

        String alias = normalizePlayerAlias(text);
        if (alias != null) {
            players.handlePlayerCommand(copyWithText(message, alias), chatId, null);
        } else if (isPlayerCommand(text)) {
            players.handlePlayerCommand(message, chatId, null);
        } else if (text.equals("🪪 Привязка аккаунта") || text.startsWith("/register")) {
            auth.handleRegisterCommand(message, chatId, null);
        } else if (text.equals("📝 Создать обращение")) {
            String username = message.getFrom().getUserName() == null
                    ? "Пользователь #" + userId
                    : "@" + message.getFrom().getUserName();
            tickets.startTicketCreation(userId, username);
        } else if (text.equals("🖥 Главное меню")) {
            sendMainMenu(chatId, userId);
        } else if (text.equals("❌ Отменить привязку") || text.startsWith("/unlink")) {
            auth.handleUnlinkCommand(message, chatId, null);
        } else if (text.equals("📜 История тикетов") || text.equals("📂 Ваши обращения")) {
            tickets.showUserTicketHistory(userId);
        } else if (text.equals("🔒 Закрыть обращение")) {
            tickets.startCloseTicketFlow(userId);
        } else if (text.equals("/cancel")) {
            tickets.cancelUserReply(userId);
        } else if (text.startsWith("/ticket_cancel")) {
            tickets.cancelTicketByUser(userId);
        } else if (text.startsWith("/ticket_info")) {
            String[] parts = text.split(" ");
            if (parts.length == 2) tickets.getTicketInfo(parts[1], userId);
            else messages.sendMessageHtml(chatId, null, "❌ <b>Использование:</b> /ticket_info TKT-0001");
        } else if (text.startsWith("/settings") || text.equals("⚙️ Настройки")) {
            if (!isAccountLinked(userId)) {
                messages.sendMessageHtml(chatId, null,
                        "❌ <b>Вы не авторизованы!</b> Для доступа к настройкам необходимо привязать аккаунт.");
                return;
            }
            settings.handleCommand(chatId);
        } else {
            sendFallback(chatId, userId);
        }
    }

    private void handleStart(Message message, String text) {
        if (text.equals("/start")) {
            sendMainMenu(message.getChatId(), message.getFrom().getId());
            return;
        }
        if (!text.startsWith("/start ")) return;
        String parameter = text.substring(7).trim();
        if (!parameter.matches("[A-Z2-9]{5}")) {
            sendMainMenu(message.getChatId(), message.getFrom().getId());
            return;
        }
        messages.sendMessageHtml(message.getChatId(), null,
                "🔐 <b>Обнаружен код привязки: " + parameter + "</b>\nНачинаю процесс привязки к аккаунту...");
        auth.handleRegisterCommand(copyWithText(message, "/register " + parameter), message.getChatId(), null);
    }

    private Message copyWithText(Message source, String text) {
        return Message.builder()
                .messageId(source.getMessageId())
                .from(source.getFrom())
                .chat(Chat.builder().id(source.getChatId()).type("private").build())
                .date(source.getDate())
                .text(text)
                .build();
    }

    private boolean isPlayerCommand(String text) {
        return text.startsWith("/civ") || text.startsWith("/money") || text.startsWith("/town")
                || text.startsWith("/bal") || text.startsWith("/balance")
                || text.startsWith("/eco") || text.startsWith("/pay");
    }

    private String normalizePlayerAlias(String text) {
        if (text.equals("/civ_info") || text.startsWith("/civ_info@")) return "/civ info";
        if (text.equals("/town_info") || text.startsWith("/town_info@")) return "/town info";
        return null;
    }

    private void sendMainMenu(Long chatId, Long userId) {
        if (isAccountLinked(userId)) {
            messages.sendMessageHtml(chatId, null,
                    "✅ <b>Аккаунт привязан.</b>\n\n" +
                            "Игровые команды лежат в /help и в меню команд Telegram. Ниже кнопки только действия по обращениям и помощь.",
                    CreateKeyboardUser.linkedMainMenu());
            return;
        }
        messages.sendMessageHtml(chatId, null,
                "🎩 Привет! Я <b>Киворт</b> — твой помощник по режиму <b>CivCraft</b>!\n\n" +
                        "📱 Благодаря мне, ты сможешь <b>управлять аккаунтом, цивилизацией или городом</b> не заходя на сервер, а также <b>обратиться</b> к моим создателям <b>за помощью</b>!\n\n" +
                        "👇 Выбери желаемое действие, <b>нажав</b> на кнопки ниже!",
                CreateKeyboardUser.mainMenu(userId, tickets));
    }

    private void sendFallback(Long chatId, Long userId) {
        if (isAccountLinked(userId)) sendPlayerHelp(chatId);
        else messages.sendMessage(chatId, null, "Напишите /start для начала работы");
    }

    private void sendPlayerHelp(Long chatId) {
        messages.sendMessageHtml(chatId, null,
                "📋 <b>Команды CivCraft</b>\n\n" +
                        "• /civ_info - получить информацию о вашей цивилизации\n" +
                        "• /town_info - получить информацию о вашем городе\n" +
                        "• /money или /bal - посмотреть баланс\n" +
                        "• <code>/pay &lt;ник&gt; &lt;сумма&gt;</code> - перевести деньги игроку");
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
