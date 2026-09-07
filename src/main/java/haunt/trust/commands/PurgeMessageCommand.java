package haunt.trust.commands;

import haunt.trust.Bot;
import haunt.trust.utils.MessageService;

public class PurgeMessageCommand {

    private Bot bot;
    private MessageService messageService;

    public PurgeMessageCommand(Bot bot, MessageService messageService) {
        this.bot = bot;
        this.messageService = messageService;
    }

//    public void execute(Long chatId, Integer threadId, int fromMessageId, int count) {
//        for (int i = 0; i < count; i++) {
//            int messageIdToDelete = fromMessageId - i;
//            DeleteMessage deleteMessage = new DeleteMessage();
//            deleteMessage.setChatId(chatId.toString());
//            deleteMessage.setMessageId(messageIdToDelete);
//            try {
//                bot.execute(deleteMessage);
//            } catch (TelegramApiException e) {
//            }
//        }
//
//        messageService.sendMessageHtml(chatId, threadId, "Удалено " + count + " сообщений.");
//    }
//
//    public void handlePurgeCommand(Update update) {
//        Message message = update.getMessage();
//        if (message == null || message.getText() == null) {
//            return;
//        }
//
//        String[] parts = message.getText().split(" ");
//        if (parts.length != 2) {
//            messageService.sendMessageHtml(message.getChatId(), message.getMessageThreadId(),
//                    "Использование команды: <code>/purge количество</code>");
//            return;
//        }
//
//        try {
//            int count = Integer.parseInt(parts[1]);
//            if (count <= 0) {
//                messageService.sendMessageHtml(message.getChatId(), message.getMessageThreadId(),
//                        "Количество должно быть больше нуля.");
//                return;
//            }
//
//            execute(message.getChatId(), message.getMessageThreadId(), message.getMessageId(), count);
//        } catch (NumberFormatException e) {
//            messageService.sendMessageHtml(message.getChatId(), message.getMessageThreadId(),
//                    "Укажите корректное число сообщений для удаления.");
//        }
//    }
}
