package haunt.trust.handlers;

import haunt.trust.NettyServerMonitor;
import haunt.trust.tickets.TicketService;
import haunt.trust.utils.MessageService;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.objects.chat.ChatFullInfo;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

public final class TicketMessageHandler {
    private static final long SUPPORT_CHAT_ID = -1002521750905L;
    private static final int SUPPORT_THREAD_ID = 5417;

    private final TelegramClient telegram;
    private final TicketService tickets;
    private final MessageService messages;
    private final AccessPolicy access;

    public TicketMessageHandler(TelegramClient telegram, TicketService tickets, MessageService messages, AccessPolicy access) {
        this.telegram = telegram;
        this.tickets = tickets;
        this.messages = messages;
        this.access = access;
    }

    public boolean handle(Message message) {
        if (isSupportReply(message) && message.getText() != null && access.canManageTickets(message.getFrom().getId())) {
            handleSupportText(message);
            return true;
        }

        Media media = Media.from(message);
        if (media == null) {
            return false;
        }
        if (message.getChatId() > 0) {
            tickets.handleMedia(message.getFrom().getId(), media.fileId(), message.getCaption(), media.type());
            return true;
        }
        if (!isSupportReply(message)) {
            return false;
        }
        if (access.canManageTickets(message.getFrom().getId())) {
            Message replyTo = message.getReplyToMessage();
            tickets.handleSupportReplyWithMedia(
                    replyTo.getMessageId(), message.getFrom().getId(), media.fileId(), media.type(),
                    message.getCaption(), content(replyTo)
            );
        }
        return true;
    }

    private void handleSupportText(Message message) {
        Message replyTo = message.getReplyToMessage();
        String text = message.getText();
        Long actorId = message.getFrom().getId();

        if (text.startsWith("/close")) {
            tickets.closeTicketFromSupportReply(replyTo.getMessageId(), actorId, text, content(replyTo));
            return;
        }
        if (text.startsWith("/ban") || text.startsWith("/banned")) {
            changeBan(message, true);
            return;
        }
        if (text.startsWith("/unban")) {
            changeBan(message, false);
            return;
        }
        if (text.equalsIgnoreCase("/info") || text.toLowerCase().startsWith("/info@")) {
            showUserInfo(message);
            return;
        }
        tickets.handleSupportReply(replyTo.getMessageId(), actorId, text, content(replyTo));
    }

    private void showUserInfo(Message message) {
        Message replyTo = message.getReplyToMessage();
        TicketService.TicketUserInfo user = tickets.getUserInfoByMessageId(replyTo.getMessageId(), content(replyTo));
        if (user == null) {
            reply(message, "❌ <b>Не удалось определить пользователя этого тикета.</b>");
            return;
        }

        NettyServerMonitor.sendAuthCommand("auth_check " + user.telegramId()).whenComplete((response, error) -> {
            String header = "👤 <b>Информация о пользователе</b>\n\n" +
                    "🎫 Тикет: <code>" + MessageService.escapeHtml(user.ticketId()) + "</code>\n" +
                    "💬 Telegram: " + getTelegramProfile(user.telegramId()) + "\n";

            if (error != null || response == null || response.startsWith("ERROR:")) {
                reply(message, header + "\n❌ <b>Не удалось проверить привязку: игровой сервер недоступен.</b>");
            } else if (response.startsWith("LINKED:")) {
                String playerName = response.substring("LINKED:".length()).trim();
                reply(message, header +
                        "   Игровой ник: <code>" + MessageService.escapeHtml(playerName) + "</code>\n" +
                        "✅ Привязка к серверу активна.");
            } else {
                reply(message, header + "\nℹ️ <b>У игрока не имеется привязки к серверу.</b>");
            }
        });
    }

    private String getTelegramProfile(Long telegramId) {
        try {
            ChatFullInfo chat = telegram.execute(GetChat.builder().chatId(telegramId).build());
            String name = ((chat.getFirstName() == null ? "" : chat.getFirstName()) + " " +
                    (chat.getLastName() == null ? "" : chat.getLastName())).trim();
            String username = chat.getUserName() == null || chat.getUserName().isBlank()
                    ? ""
                    : "@" + chat.getUserName();
            String profile = name.isBlank() ? username : username.isBlank() ? name : name + " (" + username + ")";
            return profile.isBlank() ? "Имя не указано" : MessageService.escapeHtml(profile);
        } catch (Exception ignored) {
            return "Не удалось получить профиль";
        }
    }

    private void reply(Message message, String html) {
        messages.sendMessageHtmlReplyToWithResult(
                message.getChatId(), message.getMessageThreadId(), message.getMessageId(), html
        );
    }

    private void changeBan(Message message, boolean ban) {
        Message replyTo = message.getReplyToMessage();
        String[] parts = message.getText().split(" ", 2);
        String identifier = parts.length == 2 && !parts[1].isBlank() ? parts[1].trim() : null;
        if (identifier == null) {
            Long userId = tickets.getUserIdByMessageId(replyTo.getMessageId(), content(replyTo));
            if (userId != null) identifier = String.valueOf(userId);
        }
        if (identifier == null) {
            messages.sendMessageHtml(message.getChatId(), message.getMessageThreadId(),
                    "❌ <b>Не удалось определить пользователя.</b>\n" +
                            "Укажите ID или @username: /" + (ban ? "ban" : "unban") + " &lt;id|@username&gt;");
            return;
        }
        messages.sendMessageHtml(message.getChatId(), message.getMessageThreadId(),
                ban ? tickets.banUser(identifier) : tickets.unbanUser(identifier));
    }

    private boolean isSupportReply(Message message) {
        return message.getChatId().equals(SUPPORT_CHAT_ID)
                && message.getMessageThreadId() != null
                && message.getMessageThreadId() == SUPPORT_THREAD_ID
                && message.getReplyToMessage() != null;
    }

    private String content(Message message) {
        return message.getText() != null ? message.getText() : message.getCaption();
    }

    private record Media(String fileId, String type) {
        private static Media from(Message message) {
            if (message.hasPhoto()) {
                return new Media(message.getPhoto().getLast().getFileId(), "photo");
            }
            if (message.hasVideo()) {
                return new Media(message.getVideo().getFileId(), "video");
            }
            if (message.hasDocument()) {
                return new Media(message.getDocument().getFileId(), "document");
            }
            return null;
        }
    }
}
