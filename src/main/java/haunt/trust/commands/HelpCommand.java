package haunt.trust.commands;

import haunt.trust.Bot;
import haunt.trust.config.BotConfig;
import haunt.trust.config.ConfigLoader;
import haunt.trust.utils.MessageService;
import org.telegram.telegrambots.meta.api.objects.User;

public class HelpCommand {
    private MessageService messageService;

    public HelpCommand(MessageService messageService) {
        this.messageService = messageService;
    }

    /* Topic 8 command '/help' */
    public void handleHelpCommandTopic8(Long chatId, Integer threadId) {
        if (chatId.equals(-1002521750905L) && threadId != null && threadId == 8) {
            StringBuilder helpText = new StringBuilder();
            helpText.append("📋 Вижу, кому-то нужна моя помощь? Лови несколько записей!\n\n");
            helpText.append("• /help - список доступных команд;\n");
            helpText.append("• /state - сбор статистики о кротах серверов CivCraft;\n");

            messageService.sendMessage(chatId, threadId, helpText.toString());
        } else {
            messageService.sendMessageHtml(chatId, threadId, "❌ Команда /help доступна только в <a href=\"https://t.me/c/2521750905/8\">топике 8</a> или <a href=\"https://t.me/c/2521750905\">основном чате</a>.");
        }
    }

    /* Main Chat command '/help' */
    public void handleHelpCommandMainChat(Long chatId, Integer threadId) {
        if (chatId.equals(-1002521750905L) && (threadId == null || threadId == 0)) {
            StringBuilder helpText = new StringBuilder();
            helpText.append("📋 Вижу, кому-то нужна моя помощь? Лови несколько записей!\n\n");
            helpText.append("• /help - список доступных команд;\n");
            helpText.append("• /ping - запрос к боту с целью определения состояния связи между ботом и сервером;\n");
            helpText.append("• /cmd <сервер> <команда> - отправить команду на указанный сервер CivCraft;\n");

            messageService.sendMessage(chatId, threadId, helpText.toString());
        } else {
            messageService.sendMessageHtml(chatId, threadId, "❌ Эта команда доступна только в чате <a href=\"https://t.me/c/2521750905/1\">'Флудилка'</a>.");
        }
    }
}