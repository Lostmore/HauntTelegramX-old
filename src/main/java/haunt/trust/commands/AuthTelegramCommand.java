package haunt.trust.commands;

import haunt.trust.database.SQL;
import haunt.trust.utils.MessageService;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import java.io.*;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AuthTelegramCommand {
    private final MessageService messageService;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    private static final String HOST = "localhost";
    private static final int PORT = 25422;

    public AuthTelegramCommand(MessageService messageService) {
        this.messageService = messageService;
    }

    public void handleRegisterCommand(Message msg, Long chatId, Integer threadId) {
        String text = msg.getText();
        Long userId = msg.getFrom().getId();
        String username = msg.getFrom().getUserName() != null ?
                "@" + msg.getFrom().getUserName() :
                "Пользователь #" + userId;

        // Обработка прямого запуска с кодом: /start CODE или /start register_CODE
        if (text.startsWith("/start ") && text.length() > 7) {
            String param = text.substring(7).trim();

            if (param.matches("[A-Z2-9]{5}")) {
                processAuthCodeDirect(param, userId, username, chatId, threadId);
                return;
            } else if (param.startsWith("register_") && param.length() > 9) {
                // С параметром: /start register_8RN2F
                String code = param.substring(9);
                if (code.matches("[A-Z2-9]{5}")) {
                    processAuthCodeDirect(code, userId, username, chatId, threadId);
                    return;
                }
            }
        }

        // Обычная команда /register
        if (text.equals("🪪 Привязка аккаунта") || text.equals("/register") || text.startsWith("/register ")) {
            if (text.equals("🪪 Привязка аккаунта") || text.equals("/register")) {
                // Проверка существующей привязки
                try {
                    String checkResponse = sendCommandToServer("auth_check " + userId);
                    if (checkResponse != null && checkResponse.startsWith("LINKED:")) {
                        String alreadyLinkedMessage =
                                "✅ <b>Привязка уже активна!</b>\n\n" +
                                "🎉 Теперь тебе доступно <b>управление аккаунтом, цивилизацией и городом</b> прямо из телефона!\n\n" +
                                "👇 Используй кнопки ниже:";

                        messageService.sendMessageHtml(chatId, threadId, alreadyLinkedMessage, CreateKeyboardUser.linkedMainMenu());
                        return;
                    }
                } catch (Exception e) {}

                // Инструкция с кнопкой
                String instruction = """
                🎩 <b>Прежде, чем я смогу выполнять действия с твоим аккаунтом, его необходимо привязать!</b> Давай расскажу как!
                
                🔐  <b><u>Привязка аккаунта</u></b>
                1. Зайди на сервер <b>CivCraft</b>;
                2. Используй команду <code>/res auth</code>;
                3. Ты получишь <b>5-значный код</b> <i>(Пример: 8RN2F)</i>;
                4. Пропиши в этом чате <code>/register [Код]</code> или используй <b>быструю ссылку</b>, полученную в игре.
                
                ❗️ Учти, что код действителен всего <b><u>10 минут</u></b>!
                """;

                messageService.sendMessageHtml(chatId, threadId, instruction);
                return;
            }

            // Обработка команды с кодом
            if (text.startsWith("/register ")) {
                String[] parts = text.split(" ", 2);
                if (parts.length < 2 || parts[1].trim().isEmpty()) {
                    messageService.sendMessageHtml(chatId, threadId,
                            "❌ <b>Не указан код!</b>\n" +
                                    "Использование: <code>/register КОД</code>\n" +
                                    "Пример: <code>/register 8RN2F</code>");
                    return;
                }

                String code = parts[1].trim().toUpperCase();
                processAuthCodeDirect(code, userId, username, chatId, threadId);
                return;
            }
        }
    }

    private void processAuthCodeDirect(String code, Long telegramUserId, String telegramUsername, Long chatId, Integer threadId) {
        // Проверяем формат кода
        if (!code.matches("[A-Z2-9]{5}")) {
            messageService.sendMessageHtml(chatId, threadId,
                    "❌ <b>Неверный формат кода!</b>\n" + "Код должен состоять из 5 латинских букв и цифр.\n" + "Пример: <code>8RN2F</code>");
            return;
        }

        // Отправляем запрос на сервер
        executorService.submit(() -> {
            try {
                String response = sendCommandToServer("auth_confirm " + code + " " + telegramUserId + " " + telegramUsername);

                if (response != null) {
                    if (response.startsWith("SUCCESS:")) {
                        String[] parts = response.split(":");
                        String playerName = parts.length >= 3 ? parts[2] : "Неизвестно";
                        SQL.upsertAuthorizedUser(telegramUserId, playerName);

                        String successMessage =
                                "✅ <b>Привязка успешна!</b>\n\n" +
                                "🎉 Теперь тебе доступно <b>управление аккаунтом, цивилизацией и городом</b> прямо из телефона!\n\n" +
                                "👇 Используй кнопку ниже, чтобы вернуться в главное меню!";

                        messageService.sendMessageHtml(chatId, threadId, successMessage, CreateKeyboardUser.linkedMainMenu());
                    } else if (response.contains("Неверный_или_просроченный_код")) {
                        messageService.sendMessageHtml(chatId, threadId,
                                "❌ <b>Неверный или просроченный код!</b>\n" +
                                        "Код не найден или уже использован.\n" +
                                        "Получите новый код командой <code>/res auth</code> в игре.");
                    } else if (response.contains("Telegram_уже_привязан")) {
                        messageService.sendMessageHtml(chatId, threadId,
                                "❌ <b>Этот Telegram уже привязан!</b>\n" +
                                        "Один Telegram можно привязать только к одному аккаунту.\n" +
                                        "Используйте команду <code>/unlink</code> чтобы отвязать.");
                    } else {
                        messageService.sendMessageHtml(chatId, threadId,
                                "❌ <b>Ошибка привязки!</b>\n" +
                                        "Ответ сервера: " + response);
                    }
                } else {
                    messageService.sendMessageHtml(chatId, threadId,
                            "❌ <b>Ошибка соединения!</b>\n" +
                                    "Не удалось связаться с сервером Minecraft.");
                }

            } catch (Exception e) {
                e.printStackTrace();
                messageService.sendMessageHtml(chatId, threadId,
                        "❌ <b>Ошибка соединения!</b>\n"
                                + "Сервер Minecraft недоступен.\n"
                                + "Попробуйте позже.");
            }
        });
    }

    private String sendCommandToServer(String command) throws IOException {
        try (Socket socket = new Socket(HOST, PORT);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            socket.setSoTimeout(10000);
            out.println(command);
            out.flush();

            StringBuilder response = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) break;
                response.append(line);
                break;
            }

            return response.toString();
        } catch (ConnectException e) {
            System.err.println("❌ Не удалось подключиться к серверу");
            throw e;
        } catch (SocketTimeoutException e) {
            System.err.println("❌ Таймаут подключения к серверу");
            throw e;
        }
    }

    public void handleUnlinkCommand(Message msg, Long chatId, Integer threadId) {
        Long userId = msg.getFrom().getId();

        executorService.submit(() -> {
            try {
                String checkResponse = sendCommandToServer("auth_check " + userId);

                if (checkResponse != null && checkResponse.startsWith("LINKED:")) {
                    String unlinkResponse = sendCommandToServer("auth_unlink " + userId);

                    if (unlinkResponse != null && unlinkResponse.startsWith("SUCCESS:")) {
                        SQL.markAuthorizedUserUnlinked(userId);
                        messageService.sendMessageHtml(
                                chatId,
                                threadId,
                                "✅ Привязка отменена! Возвращаю тебя в главное меню!",
                                CreateKeyboardUser.mainMenu(userId, null)
                        );
                    } else {
                        messageService.sendMessageHtml(chatId, threadId,
                                "❌ <b>Ошибка отвязки!</b>\n" +
                                        "Ответ сервера: " + (unlinkResponse != null ? unlinkResponse : "Нет ответа"));
                    }
                } else {
                    messageService.sendMessageHtml(chatId, threadId,
                            "ℹ️ <b>Telegram не привязан!</b>\n" +
                                    "Ваш Telegram еще не привязан к аккаунту Minecraft.");
                }

            } catch (Exception e) {
                e.printStackTrace();
                messageService.sendMessageHtml(chatId, threadId,
                        "❌ <b>Ошибка соединения!</b>\n" +
                                "Сервер Minecraft недоступен.");
            }
        });
    }

    public CompletableFuture<String> sendAuthCommandViaNetty(String command) {
        CompletableFuture<String> future = new CompletableFuture<>();

        executorService.submit(() -> {
            try {
                String response = sendCommandToServer(command);
                future.complete(response);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }
}
