package haunt.trust.commands;

import haunt.trust.database.SQL;
import haunt.trust.utils.MessageService;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.ArrayList;
import java.util.List;

public class SettingsCommand {
    private final MessageService messageService;
    private final TelegramClient telegramClient;

    public SettingsCommand(MessageService messageService, TelegramClient telegramClient) {
        this.messageService = messageService;
        this.telegramClient = telegramClient;
    }

    public void handleCommand(Long chatId) {
        sendSettingsMenu(chatId, null);
    }

    public void handleCallback(Long chatId, Integer messageId, String data) {
        if (data.startsWith("settings_toggle_")) {
            String setting = data.substring("settings_toggle_".length());
            SQL.toggleSetting(chatId, setting);
            sendSettingsMenu(chatId, messageId);
        }
    }

    private void sendSettingsMenu(Long chatId, Integer messageId) {
        boolean borderEnabled = SQL.isSettingEnabled(chatId, "notify_border");
        boolean structEnabled = SQL.isSettingEnabled(chatId, "notify_struct");
        boolean govEnabled = SQL.isSettingEnabled(chatId, "notify_gov");

        String text = "⚙️ *Настройки уведомлений*\n\n" +
                "Здесь вы можете настроить, какие уведомления от сервера вы хотите получать в личные сообщения\\.";

        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(createButton(
                (borderEnabled ? "✅" : "❌") + " Песечение территории",
                "settings_toggle_notify_border"
        )));
        
        rows.add(new InlineKeyboardRow(createButton(
                (structEnabled ? "✅" : "❌") + " Постройки и Чудеса",
                "settings_toggle_notify_struct"
        )));
        
        rows.add(new InlineKeyboardRow(createButton(
                (govEnabled ? "✅" : "❌") + " Смена гос. строя",
                "settings_toggle_notify_gov"
        )));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(rows);

        try {
            if (messageId == null) {
                SendMessage message = SendMessage.builder()
                        .chatId(chatId)
                        .text(text)
                        .parseMode("MarkdownV2")
                        .replyMarkup(markup)
                        .build();
                telegramClient.execute(message);
            } else {
                EditMessageText edit = EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(messageId)
                        .text(text)
                        .parseMode("MarkdownV2")
                        .replyMarkup(markup)
                        .build();
                telegramClient.execute(edit);
            }
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private InlineKeyboardButton createButton(String text, String callbackData) {
        InlineKeyboardButton button = new InlineKeyboardButton(text);
        button.setCallbackData(callbackData);
        return button;
    }
}
