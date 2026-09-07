package haunt.trust.commands;

import haunt.trust.Bot;
import haunt.trust.NettyServerMonitor;
import haunt.trust.utils.MessageService;
import org.telegram.telegrambots.meta.generics.TelegramClient;

public class PingCommand {

    private final MessageService messageService;
    private final NettyServerMonitor serverMonitor;

    public PingCommand(TelegramClient telegramClient, MessageService messageService, NettyServerMonitor serverMonitor) {
        this.messageService = messageService;
        this.serverMonitor = serverMonitor;
    }

    public PingCommand(Bot bot, MessageService messageService, NettyServerMonitor serverMonitor) {
        this(bot.getTelegramClient(), messageService, serverMonitor);
    }

    public void handlePingCommand(Long chatId, Integer threadId) {
        if (chatId.equals(-1002521750905L) && (threadId == null || threadId == 0)) {
            StringBuilder response = new StringBuilder();
            response.append("🎾 Понг! Состояние серверов:\n\n");

            String[] serverNames = serverMonitor.getServerNames();
            boolean[] statuses = serverMonitor.getServerStatus();
            int[] retryCounts = serverMonitor.getRetryCounts();
            int maxRetries = serverMonitor.getMaxRetries();

            for (int i = 0; i < serverNames.length; i++) {
                String emoji;

                if (statuses[i]) {
                    emoji = "🟢";
                } else if (retryCounts[i] > 0 && retryCounts[i] <= maxRetries) {
                    emoji = "🟡";
                } else {
                    emoji = "🔴";
                }

                response.append("• ").append(emoji).append(" ").append("`" +serverNames[i] +"`")
                        .append(" - ");

                if (statuses[i]) {
                    response.append("связь установлена\n");
                } else if (retryCounts[i] > 0 && retryCounts[i] <= maxRetries) {
                    response.append("попытка установки связи\n");
                } else {
                    response.append("связь разорвана\n");
                }
            }

            messageService.sendMessage(chatId, threadId, response.toString());
        } else {
            messageService.sendMessageHtml(chatId, threadId, "❌ Эта команда доступна только в чате <a href=\"https://t.me/c/2521750905/1\">'Флудилка'</a>.");
        }
    }
}