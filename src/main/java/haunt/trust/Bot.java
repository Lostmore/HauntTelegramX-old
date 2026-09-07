package haunt.trust;

import haunt.trust.commands.ClearCivCraftUsersCommand;
import haunt.trust.config.ConfigLoader;
import haunt.trust.handlers.AccessPolicy;
import haunt.trust.handlers.AdminUpdateHandler;
import haunt.trust.handlers.PrivateMessageHandler;
import haunt.trust.handlers.TicketMessageHandler;
import haunt.trust.tickets.TicketService;
import haunt.trust.utils.MessageService;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class Bot {
    private final TelegramClient telegramClient;
    private final NettyServerMonitor serverMonitor;
    private final MessageService messages;
    private final TicketMessageHandler ticketMessages;
    private final PrivateMessageHandler privateMessages;
    private final AdminUpdateHandler adminUpdates;

    public Bot(TelegramClient telegramClient) {
        this.telegramClient = telegramClient;
        ConfigLoader.getInstance();
        this.serverMonitor = new NettyServerMonitor(this);
        this.serverMonitor.startMonitoring();

        this.messages = new MessageService(telegramClient);
        TicketService tickets = new TicketService(messages);
        AccessPolicy access = new AccessPolicy();
        ClearCivCraftUsersCommand clearUsers = new ClearCivCraftUsersCommand(messages);
        this.ticketMessages = new TicketMessageHandler(telegramClient, tickets, messages, access);
        this.privateMessages = new PrivateMessageHandler(telegramClient, messages, tickets, access, clearUsers);
        this.adminUpdates = new AdminUpdateHandler(telegramClient, serverMonitor, messages, tickets, access, clearUsers);
        registerBotCommands();
    }

    public TelegramClient getTelegramClient() {
        return telegramClient;
    }

    public void onUpdateReceived(Update update) {
        if (update.hasMessage()) {
            Message message = update.getMessage();
            if (debugCustomEmojiIds(message) || ticketMessages.handle(message)) {
                return;
            }
            if (message.hasText()) {
                if (message.getChatId() > 0) privateMessages.handle(message);
                else adminUpdates.handleMessage(message);
            }
            return;
        }
        if (update.hasCallbackQuery()) {
            adminUpdates.handleCallback(update);
        }
    }

    private boolean debugCustomEmojiIds(Message message) {
        List<MessageEntity> entities = new ArrayList<>();
        if (message.getEntities() != null) entities.addAll(message.getEntities());
        if (message.getCaptionEntities() != null) entities.addAll(message.getCaptionEntities());

        Set<String> emojiIds = new LinkedHashSet<>();
        for (MessageEntity entity : entities) {
            if ("custom_emoji".equals(entity.getType())
                    && entity.getCustomEmojiId() != null
                    && !entity.getCustomEmojiId().isBlank()) {
                emojiIds.add(entity.getCustomEmojiId());
            }
        }
        if (emojiIds.isEmpty()) return false;

        String sender = message.getFrom() == null ? "unknown" : message.getFrom().getUserName();
        System.out.println("Custom emoji IDs from @" + sender + ": " + String.join(", ", emojiIds));
        if (message.getChatId() <= 0) return false;

        StringBuilder response = new StringBuilder(" <b>Custom emoji ID</b>\n\n");
        int index = 1;
        for (String emojiId : emojiIds) {
            response.append(index++).append(". <code>").append(MessageService.escapeHtml(emojiId)).append("</code>\n");
        }
        messages.sendMessageHtml(message.getChatId(), null, response.toString());
        return true;
    }

    private void registerBotCommands() {
        List<BotCommand> commands = List.of(
                new BotCommand("start", "главное меню"),
                new BotCommand("help", "список команд CivCraft"),
                new BotCommand("register", "привязать аккаунт"),
                new BotCommand("unlink", "удаляет привязку Telegram аккаунта"),
                new BotCommand("civ_info", "информация о вашей цивилизации"),
                new BotCommand("town_info", "информация о вашем городе"),
                new BotCommand("money", "ваш баланс"),
                new BotCommand("bal", "ваш баланс")
        );
        try {
            telegramClient.execute(SetMyCommands.builder()
                    .commands(commands)
                    .scope(new BotCommandScopeDefault())
                    .build());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}
