package haunt.trust.commands;

import haunt.trust.NettyServerMonitor;
import haunt.trust.utils.MessageService;
import haunt.trust.Bot;

import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static haunt.trust.config.ConfigLoader.config;

public class InlineKeyboardCommand {
    private final TelegramClient telegramClient;
    private final NettyServerMonitor nettyServerMonitor;
    private final MessageService messageService;

    public InlineKeyboardCommand(TelegramClient telegramClient, NettyServerMonitor nettyServerMonitor, MessageService messageService) {
        this.telegramClient = telegramClient;
        this.nettyServerMonitor = nettyServerMonitor;
        this.messageService = messageService;
    }

    public InlineKeyboardCommand(Bot bot, NettyServerMonitor nettyServerMonitor, MessageService messageService) {
        this(bot.getTelegramClient(), nettyServerMonitor, messageService);
    }

    public void handlePanelCommand(Long chatId, Integer threadId) {
        System.out.println("DEBUG: Запрос панели в чат " + chatId + ", тред " + threadId);

        if (!chatId.equals(-1002521750905L)) {
            System.out.println("DEBUG: Не основная группа");
            return;
        }

        if (threadId == null || threadId == 6) {
            try {
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId.toString())
                        .messageThreadId(threadId != null ? threadId : 6)
                        .text("Панель управления серверами:")
                        .replyMarkup(createKeyboardMarkup())
                        .parseMode("HTML")
                        .build());
                System.out.println("DEBUG: Панель отправлена");
            } catch (Exception e) {
                System.out.println("DEBUG: Ошибка: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.out.println("DEBUG: Неправильный тред: " + threadId);
        }
    }

    public void handleCallbackQuery(Update update) {
        CallbackQuery callbackQuery = update.getCallbackQuery();
        String data = callbackQuery.getData();
        String callbackId = callbackQuery.getId();
        Long userId = callbackQuery.getFrom().getId();

        try {
            if (!config.getAutorized_users().contains(userId)) {
                telegramClient.execute(AnswerCallbackQuery.builder()
                        .callbackQueryId(callbackId)
                        .text("❌ У вас нет прав для этого действия")
                        .showAlert(true)
                        .build());
                return;
            }

            try {
                telegramClient.execute(AnswerCallbackQuery.builder()
                        .callbackQueryId(callbackId)
                        .text("Обработка...")
                        .showAlert(false)
                        .build());
            } catch (Exception e) {
                return;
            }

            if (callbackQuery.getMessage() instanceof Message) {
                Message msg = (Message) callbackQuery.getMessage();
                Long chatId = msg.getChatId();
                Integer threadId = msg.getMessageThreadId();

                if (!chatId.equals(-1002521750905L) || threadId == null) {
                    return;
                }
                switch (data) {
                    case "server_cmd":
                        handleServerCommandRequest(chatId, threadId);
                        break;
                    case "info":
                        handleInfoRequest(chatId, threadId);
                        break;
                    default:
                        if (data.startsWith("restart_") || data.startsWith("stop_") || "cancel_action".equals(data)) {
                            handleServerActions(data, chatId, threadId);
                        } else {
                            sendDefaultResponse(chatId, threadId, data);
                        }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            try {
                telegramClient.execute(AnswerCallbackQuery.builder()
                        .callbackQueryId(callbackId)
                        .text("❌ Ошибка обработки запроса")
                        .showAlert(true)
                        .build());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    private void handleServerCommandRequest(Long chatId, Integer threadId) throws Exception {
        telegramClient.execute(SendMessage.builder()
                .chatId(chatId.toString())
                .messageThreadId(threadId)
                .text("⌨️ Введите команду для сервера (например: /say Привет)")
                .build());
    }

    private void handleInfoRequest(Long chatId, Integer threadId) throws Exception {
        telegramClient.execute(SendMessage.builder()
                .chatId(chatId.toString())
                .messageThreadId(threadId)
                .text("⏳ Запрашиваю статистику сервера...")
                .build());

        String stats = getServerStats(chatId, threadId);
        telegramClient.execute(SendMessage.builder()
                .chatId(chatId.toString())
                .messageThreadId(threadId)
                .text(stats)
                .replyMarkup(createKeyboardMarkup())
                .build());
    }

    private void sendDefaultResponse(Long chatId, Integer threadId, String callbackData) throws Exception {
        String response = getCallbackResponse(callbackData);
        telegramClient.execute(SendMessage.builder()
                .chatId(chatId.toString())
                .messageThreadId(threadId)
                .text(response)
                .replyMarkup(createKeyboardMarkup())
                .build());
    }

    private void handleServerActions(String action, Long chatId, Integer threadId) {
        int serverIndex = -1;
        for (int i = 0; i < nettyServerMonitor.getThreadIds().length; i++) {
            if (nettyServerMonitor.getThreadIds()[i] == threadId) {
                serverIndex = i;
                break;
            }
        }

        if (serverIndex == -1) {
            messageService.sendMessage(chatId, threadId, "❌ Не удалось определить сервер");
            return;
        }

        String host = nettyServerMonitor.getHosts()[serverIndex];
        int port = nettyServerMonitor.getPorts()[serverIndex];
        String serverName = nettyServerMonitor.getServerNames()[serverIndex];

        switch (action) {
            case "restart_true":
                nettyServerMonitor.sendCommandToServer(host, port, "txc restart true");
                messageService.sendMessage(chatId, threadId, "♻️ Сервер " + serverName + " будет перезагружен принудительно.");
                break;
            case "restart_false":
                nettyServerMonitor.sendCommandToServer(host, port, "txc restart false");
                messageService.sendMessage(chatId, threadId, "🔃 Сервер " + serverName + " будет перезагружен планово через 5 минут.");
                break;
            case "stop_confirm":
                nettyServerMonitor.sendCommandToServer(host, port, "txc stop");
                messageService.sendMessage(chatId, threadId, "🛑 Сервер " + serverName + " будет ОСТАНОВЛЕН");
                break;
            case "cancel_action":
                messageService.sendMessage(chatId, threadId, "❌ Действие отменено");
                break;
        }
    }

    private String getServerStats(Long chatId, Integer threadId) {
        int serverIndex = -1;
        for (int i = 0; i < nettyServerMonitor.getThreadIds().length; i++) {
            if (nettyServerMonitor.getThreadIds()[i] == threadId) {
                serverIndex = i;
                break;
            }
        }

        if (serverIndex == -1) {
            return "❌ Не удалось определить сервер";
        }

        try {
            String stats = nettyServerMonitor.getServerStats(serverIndex).get(10, TimeUnit.SECONDS);
            return "📊 Статистика " + nettyServerMonitor.getServerNames()[serverIndex] + ":\n" + stats;
        } catch (Exception e) {
            return "❌ Ошибка получения статистики: " + e.getMessage();
        }
    }

    private InlineKeyboardMarkup createKeyboardMarkup() {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        InlineKeyboardRow row1 = new InlineKeyboardRow();
        row1.add(InlineKeyboardButton.builder().text("🛠 Управление").callbackData("manage").build());
        row1.add(InlineKeyboardButton.builder().text("ℹ️ Информация").callbackData("info").build());
        rows.add(row1);

        InlineKeyboardRow row2 = new InlineKeyboardRow();
        row2.add(InlineKeyboardButton.builder().text("💻 Отправить команду").callbackData("server_cmd").build());
        rows.add(row2);

        return InlineKeyboardMarkup.builder()
                .keyboard(rows)
                .build();
    }

    private String getCallbackResponse(String callbackData) {
        switch (callbackData) {
            case "manage":
                return "🔧 Вы выбрали управление. Доступные команды:\n" +
                        "/restart - Перезапустить сервер\n" +
                        "/stop - Остановить сервер";
            case "info":
                return "ℹ️ Нажмите кнопку ещё раз для обновления статистики";
            case "server_cmd":
                return "cmd";
            case "restart_true":
            case "restart_false":
            case "stop_confirm":
            case "cancel_action":
                return "";
            default:
                return "❓ Неизвестная команда.";
        }
    }

    public InlineKeyboardMarkup createRestartConfirmKeyboard() {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        InlineKeyboardRow row1 = new InlineKeyboardRow();
        row1.add(InlineKeyboardButton.builder()
                .text("✅ Да, немедленно")
                .callbackData("restart_true")
                .build());
        row1.add(InlineKeyboardButton.builder()
                .text("🔄 Да, планово")
                .callbackData("restart_false")
                .build());
        rows.add(row1);

        InlineKeyboardRow row2 = new InlineKeyboardRow();
        row2.add(InlineKeyboardButton.builder()
                .text("❌ Отмена")
                .callbackData("cancel_action")
                .build());
        rows.add(row2);

        return InlineKeyboardMarkup.builder()
                .keyboard(rows)
                .build();
    }

    public InlineKeyboardMarkup createStopConfirmKeyboard() {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        InlineKeyboardRow row1 = new InlineKeyboardRow();
        row1.add(InlineKeyboardButton.builder()
                .text("✅ Да, остановить")
                .callbackData("stop_confirm")
                .build());
        rows.add(row1);

        InlineKeyboardRow row2 = new InlineKeyboardRow();
        row2.add(InlineKeyboardButton.builder()
                .text("❌ Отмена")
                .callbackData("cancel_action")
                .build());
        rows.add(row2);

        return InlineKeyboardMarkup.builder()
                .keyboard(rows)
                .build();
    }
}