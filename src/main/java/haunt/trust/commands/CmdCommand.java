package haunt.trust.commands;

import haunt.trust.Bot;
import haunt.trust.NettyServerMonitor;
import haunt.trust.utils.MessageService;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Locale;

public class CmdCommand {
    private final MessageService messageService;
    private final NettyServerMonitor serverMonitor;

    public CmdCommand(TelegramClient telegramClient, MessageService messageService, NettyServerMonitor serverMonitor) {
        this.messageService = messageService;
        this.serverMonitor = serverMonitor;
    }

    public CmdCommand(Bot bot, MessageService messageService, NettyServerMonitor serverMonitor) {
        this(bot.getTelegramClient(), messageService, serverMonitor);
    }

    public void handleCmdCommand(String text, Long chatId, Integer threadId) {
        if (chatId.equals(-1002521750905L) && (threadId == null || threadId == 0)) {
            String[] parts = text.trim().split(" ", 3);

            if (parts.length < 3) {
                messageService.sendMessage(chatId, threadId, "❌ Использование: /cmd <сервер> <команда>");
                return;
            }

            String serverInput = parts[1].toLowerCase(Locale.ROOT);
            String command = parts[2];

            String[] hosts = serverMonitor.getHosts();
            int[] ports = serverMonitor.getPorts();
            String[] serverNames = serverMonitor.getServerNames();

            boolean found = false;

            for (int i = 0; i < serverNames.length; i++) {
                if (serverNames[i].toLowerCase(Locale.ROOT).equals(serverInput)) {
                    String host = hosts[i];
                    int port = ports[i];
                    String serverName = serverNames[i];

                    serverMonitor.sendCommandToServer(host, port, command);
                    messageService.sendMessage(chatId, threadId, "📤 Команда отправлена: `" + command + "` на сервер: `" + serverName + "`");
                    found = true;
                    break;
                }
            }

            if (!found) {
                messageService.sendMessage(chatId, threadId, "❌ Неизвестный сервер: " + serverInput);
            }

        } else {
            messageService.sendMessageHtml(chatId, threadId, "❌ Эта команда доступна только в чате <a href=\"https://t.me/c/2521750905/1\">'Флудилка'</a>.");
        }
    }

    public void handleServerCommand(String command, Long chatId, Integer threadId) {
        if (chatId.equals(-1002521750905L) && threadId != null) {
            int serverIndex = -1;
            int[] threadIds = serverMonitor.getThreadIds();

            for (int i = 0; i < threadIds.length; i++) {
                if (threadIds[i] == threadId) {
                    serverIndex = i;
                    break;
                }
            }

            if (serverIndex == -1) {
                messageService.sendMessage(chatId, threadId, "❌ Не удалось определить сервер для этого треда");
                return;
            }

            String host = serverMonitor.getHosts()[serverIndex];
            int port = serverMonitor.getPorts()[serverIndex];
            String serverName = serverMonitor.getServerNames()[serverIndex];

            serverMonitor.sendCommandToServer(host, port, command);
            messageService.sendMessage(chatId, threadId,
                    "📤 Команда отправлена на `" + serverName + "`:\n\n`" + command + "`");
        }
    }
}