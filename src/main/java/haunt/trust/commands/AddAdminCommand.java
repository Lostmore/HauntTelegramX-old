package haunt.trust.commands;

import haunt.trust.Bot;
import haunt.trust.config.BotConfig;
import haunt.trust.config.ConfigLoader;
import haunt.trust.utils.MessageService;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

public class AddAdminCommand {

    private TelegramClient telegramClient;
    private BotConfig config;
    private MessageService messageService;

    public AddAdminCommand(TelegramClient telegramClient, MessageService messageService) {
        this.telegramClient = telegramClient;
        this.config = ConfigLoader.getInstance();
        this.messageService = messageService;
    }

    public AddAdminCommand(Bot bot, MessageService messageService) {
        this(bot.getTelegramClient(), messageService);
    }

    public void execute(User user, Long chatId, Integer threadId) {
        Long newAdminId = user.getId();
        if (!config.getAutorized_users().contains(newAdminId)) {
            config.getAutorized_users().add(newAdminId);

            String mentionText;
            if (user.getUserName() != null && !user.getUserName().isEmpty()) {
                mentionText = "<a href=\"https://t.me/" + user.getUserName() + "\">@" + user.getUserName() + "</a>";
            } else {
                mentionText = "<a href=\"tg://user?id=" + newAdminId + "\">пользователь</a>";
            }

            messageService.sendMessageHtml(chatId, threadId,
                    "👽 " + mentionText + " поздравляю с получением группы <b>admin</b>!");
            ConfigLoader.saveConfig();
        } else {
            messageService.sendMessageHtml(chatId, threadId, "❌ Этот пользователь уже является админом.");
        }
    }

    public void executeSupport(User user, Long chatId, Integer threadId) {
        Long newSupportId = user.getId();
        if (!config.getSupport_users().contains(newSupportId)) {
            config.getSupport_users().add(newSupportId);

            String mentionText;
            if (user.getUserName() != null && !user.getUserName().isEmpty()) {
                mentionText = "<a href=\"https://t.me/" + user.getUserName() + "\">@" + user.getUserName() + "</a>";
            } else {
                mentionText = "<a href=\"tg://user?id=" + newSupportId + "\">user</a>";
            }

            messageService.sendMessageHtml(chatId, threadId, mentionText + " now has <b>support</b> role.");
            ConfigLoader.saveConfig();
        } else {
            messageService.sendMessageHtml(chatId, threadId, "This user already has support role.");
        }
    }

    public void handleAddAdminCommand(String text, Long chatId, Integer threadId) {
        if (chatId.equals(-1002521750905L) && (threadId == null || threadId == 0)) {
            String[] parts = text.split(" ");
            if (parts.length > 1) {
                try {
                    Long newAdminId = Long.parseLong(parts[1]);
                    GetChatMember getChatMember = GetChatMember.builder()
                            .chatId(chatId.toString())
                            .userId(newAdminId)
                            .build();

                    try {
                        ChatMember chatMember = telegramClient.execute(getChatMember);
                        User user = chatMember.getUser();
                        execute(user, chatId, threadId);

                    } catch (TelegramApiException e) {
                        e.printStackTrace();
                        messageService.sendMessageHtml(chatId, threadId, "⚠️ Не удалось получить информацию о пользователе.");
                    }

                } catch (NumberFormatException e) {
                    messageService.sendMessageHtml(chatId, threadId, "❌ Некорректный ID пользователя.");
                }
            } else {
                messageService.sendMessageHtml(chatId, threadId, "❌ Укажите ID пользователя.");
            }
        } else {
            messageService.sendMessageHtml(chatId, threadId, "❌ Эта команда доступна только в чате <a href=\"https://t.me/c/2521750905/1\">'Флудилка'</a>.");
        }
    }

    public void handleAddSupportCommand(String text, Long chatId, Integer threadId) {
        if (chatId.equals(-1002521750905L) && (threadId == null || threadId == 0)) {
            String[] parts = text.split(" ");
            if (parts.length > 1) {
                try {
                    Long newSupportId = Long.parseLong(parts[1]);
                    GetChatMember getChatMember = GetChatMember.builder()
                            .chatId(chatId.toString())
                            .userId(newSupportId)
                            .build();

                    try {
                        ChatMember chatMember = telegramClient.execute(getChatMember);
                        User user = chatMember.getUser();
                        executeSupport(user, chatId, threadId);

                    } catch (TelegramApiException e) {
                        e.printStackTrace();
                        messageService.sendMessageHtml(chatId, threadId, "Could not get user info.");
                    }

                } catch (NumberFormatException e) {
                    messageService.sendMessageHtml(chatId, threadId, "Invalid user ID.");
                }
            } else {
                messageService.sendMessageHtml(chatId, threadId, "Usage: /addsupport <telegram_id>");
            }
        } else {
            messageService.sendMessageHtml(chatId, threadId, "This command is available only in the main chat.");
        }
    }
}
