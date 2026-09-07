package haunt.trust.utils;

import haunt.trust.Bot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

public class MessageService {

    private TelegramClient telegramClient;

    public static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }

        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    public MessageService(Bot bot) {
        this.telegramClient = bot.getTelegramClient();
    }

    public MessageService(TelegramClient telegramClient) {
        this.telegramClient = telegramClient;
    }

    public void sendMessage(Long chatId, Integer threadId, String text) {
        String safeText = text
                .replace("_", "\\_")
                .replace("*", "\\*")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("~", "\\~")
                .replace(">", "\\>")
                .replace("#", "\\#")
                .replace("+", "\\+")
                .replace("-", "\\-")
                .replace("=", "\\=")
                .replace("|", "\\|")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace(".", "\\.")
                .replace("!", "\\!");

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(safeText)
                .parseMode("MarkdownV2")
                .messageThreadId(threadId)
                .build();

        try {
            telegramClient.execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void sendMessageHtml(Long chatId, Integer threadId, String htmlText) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(htmlText)
                .parseMode("HTML")
                .messageThreadId(threadId)
                .build();

        try {
            telegramClient.execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void sendMessageMarkdownV2Raw(Long chatId, Integer threadId, String markdownText) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(markdownText)
                .parseMode("MarkdownV2")
                .messageThreadId(threadId)
                .build();

        try {
            telegramClient.execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void sendMessageHtml(Long chatId, Integer threadId, String htmlText, ReplyKeyboard replyMarkup) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(htmlText)
                .parseMode("HTML")
                .replyMarkup(replyMarkup)
                .messageThreadId(threadId)
                .build();

        try {
            telegramClient.execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void editMessageHtml(Long chatId, Integer messageId, String htmlText, InlineKeyboardMarkup replyMarkup) {
        EditMessageText message = EditMessageText.builder()
                .chatId(chatId.toString())
                .messageId(messageId)
                .text(htmlText)
                .parseMode("HTML")
                .replyMarkup(replyMarkup)
                .build();

        try {
            telegramClient.execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void answerCallback(String callbackId, String text, boolean showAlert) {
        try {
            telegramClient.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId)
                    .text(text)
                    .showAlert(showAlert)
                    .build());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public Integer sendMessageHtmlWithResult(Long chatId, Integer threadId, String htmlText) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(htmlText)
                .parseMode("HTML")
                .messageThreadId(threadId)
                .build();

        try {
            var result = telegramClient.execute(message);
            return result.getMessageId();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public Integer sendMessageHtmlWithResult(Long chatId, Integer threadId, String htmlText, ReplyKeyboard replyMarkup) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(htmlText)
                .parseMode("HTML")
                .replyMarkup(replyMarkup)
                .messageThreadId(threadId)
                .build();

        try {
            var result = telegramClient.execute(message);
            return result.getMessageId();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public Integer sendMessageHtmlReplyToWithResult(Long chatId, Integer threadId, Integer replyToMessageId, String htmlText) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(htmlText)
                .parseMode("HTML")
                .messageThreadId(threadId)
                .replyToMessageId(replyToMessageId)
                .build();

        try {
            var result = telegramClient.execute(message);
            return result.getMessageId();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public Integer sendPhotoWithCaption(Long chatId, Integer threadId, String photoFileId, String caption) {
        try {
            SendPhoto sendPhoto = SendPhoto.builder()
                    .chatId(chatId.toString())
                    .messageThreadId(threadId)
                    .photo(new InputFile(photoFileId))
                    .caption(caption)
                    .parseMode(ParseMode.HTML)
                    .build();

            var message = telegramClient.execute(sendPhoto);
            return message.getMessageId();
        } catch (TelegramApiException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Отправка видео с подписью
     */
    public Integer sendVideoWithCaption(Long chatId, Integer threadId, String videoFileId, String caption) {
        try {
            SendVideo sendVideo = SendVideo.builder()
                    .chatId(chatId.toString())
                    .messageThreadId(threadId)
                    .video(new InputFile(videoFileId))
                    .caption(caption)
                    .parseMode(ParseMode.HTML)
                    .build();

            var message = telegramClient.execute(sendVideo);
            return message.getMessageId();
        } catch (TelegramApiException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Отправка документа с подписью
     */
    public Integer sendDocumentWithCaption(Long chatId, Integer threadId, String documentFileId, String caption) {
        try {
            SendDocument sendDocument = SendDocument.builder()
                    .chatId(chatId.toString())
                    .messageThreadId(threadId)
                    .document(new InputFile(documentFileId))
                    .caption(caption)
                    .parseMode(ParseMode.HTML)
                    .build();

            var message = telegramClient.execute(sendDocument);
            return message.getMessageId();
        } catch (TelegramApiException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Отправка медиафайла в личные сообщения (без threadId)
     */
    public void sendMediaToUser(Long userId, String fileId, String mediaType, String caption) {
        sendMediaToUserWithResult(userId, fileId, mediaType, caption);
    }

    public Integer sendMediaToUserWithResult(Long userId, String fileId, String mediaType, String caption) {
        try {
            switch (mediaType.toLowerCase()) {
                case "photo":
                    SendPhoto sendPhoto = SendPhoto.builder()
                            .chatId(userId.toString())
                            .photo(new InputFile(fileId))
                            .caption(caption)
                            .parseMode(ParseMode.HTML)
                            .build();
                    var photoMsg = telegramClient.execute(sendPhoto);
                    return photoMsg.getMessageId();

                case "video":
                    SendVideo sendVideo = SendVideo.builder()
                            .chatId(userId.toString())
                            .video(new InputFile(fileId))
                            .caption(caption)
                            .parseMode(ParseMode.HTML)
                            .build();
                    var videoMsg = telegramClient.execute(sendVideo);
                    return videoMsg.getMessageId();

                case "document":
                    SendDocument sendDocument = SendDocument.builder()
                            .chatId(userId.toString())
                            .document(new InputFile(fileId))
                            .caption(caption)
                            .parseMode(ParseMode.HTML)
                            .build();
                    var documentMsg = telegramClient.execute(sendDocument);
                    return documentMsg.getMessageId();
            }
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
        return null;
    }

    public Integer sendMediaToSupport(String fileId, String mediaType, String caption) {
        try {
            switch (mediaType.toLowerCase()) {
                case "photo":
                    SendPhoto sendPhoto = SendPhoto.builder()
                            .chatId("-1002521750905")
                            .messageThreadId(5417)
                            .photo(new InputFile(fileId))
                            .caption(caption)
                            .parseMode(ParseMode.HTML)
                            .build();
                    var photoMsg = telegramClient.execute(sendPhoto);
                    return photoMsg.getMessageId();

                case "video":
                    SendVideo sendVideo = SendVideo.builder()
                            .chatId("-1002521750905")
                            .messageThreadId(5417)
                            .video(new InputFile(fileId))
                            .caption(caption)
                            .parseMode(ParseMode.HTML)
                            .build();
                    var videoMsg = telegramClient.execute(sendVideo);
                    return videoMsg.getMessageId();

                case "document":
                    SendDocument sendDocument = SendDocument.builder()
                            .chatId("-1002521750905")
                            .messageThreadId(5417)
                            .document(new InputFile(fileId))
                            .caption(caption)
                            .parseMode(ParseMode.HTML)
                            .build();
                    var documentMsg = telegramClient.execute(sendDocument);
                    return documentMsg.getMessageId();

                default:
                    return null;
            }
        } catch (TelegramApiException e) {
            e.printStackTrace();
            return null;
        }
    }
}
