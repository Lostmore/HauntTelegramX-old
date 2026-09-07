package haunt.trust.commands;

import haunt.trust.NettyServerMonitor;
import haunt.trust.utils.MessageService;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.concurrent.TimeUnit;

public class UnlinkTelegramCommand {

    private final MessageService messageService;

    public UnlinkTelegramCommand(MessageService messageService) {
        this.messageService = messageService;
    }

    public void handleUnlinkCommand(Message msg, Long chatId, Integer threadId) {
        String text = msg.getText();
        String[] args = text.split(" ");

        if (args.length != 2) {
            messageService.sendMessage(chatId, threadId, "❌ Используй: /unlink <код>");
            return;
        }

        String code = args[1].trim().toUpperCase();

        try {
            String response = NettyServerMonitor.sendAuthUnlinkRequest(code, msg.getFrom().getId()).get(5, TimeUnit.SECONDS);

            if (response.startsWith("auth_unlink_ok")) {
                String uuid = response.split(" ")[1];
                messageService.sendMessage(chatId, threadId, "✅ Telegram успешно отвязан от игрока " + uuid);
            } else if (response.contains("invalid_code")) {
                messageService.sendMessage(chatId, threadId, "❌ Неверный или устаревший код!");
            } else if (response.contains("not_linked_to_this_id")) {
                messageService.sendMessage(chatId, threadId, "⚠️ Этот код не связан с вашим аккаунтом Telegram!");
            } else {
                messageService.sendMessage(chatId, threadId, "❌ Не удалось отвязать аккаунт. Попробуйте позже.");
            }

        } catch (Exception e) {
            e.printStackTrace();
            messageService.sendMessage(chatId, threadId, "⚠️ Ошибка соединения с сервером Minecraft.");
        }
    }
}
