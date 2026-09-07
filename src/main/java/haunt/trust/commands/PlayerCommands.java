package haunt.trust.commands;

import haunt.trust.utils.MessageService;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.User;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.*;

public class PlayerCommands {
    private final MessageService messageService;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    private static final String HOST = "localhost";
    private static final int PORT = 25422;

    public PlayerCommands(MessageService messageService) {
        this.messageService = messageService;
    }

    public void handlePlayerCommand(Message msg, Long chatId, Integer threadId) {
        String text = msg.getText();
        User user = msg.getFrom();
        Long telegramId = user.getId();

        String commandId = UUID.randomUUID().toString().substring(0, 8);

        if (isAllowedCommand(text)) {
            sendCommandToServer(telegramId, commandId, text, chatId, threadId);
        } else {
            messageService.sendMessageHtml(chatId, threadId,
                    "❌ <b>Команда не разрешена!</b>\n" +
                            "Разрешенные команды:\n" +
                            "• <code>/civ info</code> - информация о цивилизации\n" +
                            "• <code>/money</code> или <code>/bal</code> - баланс\n" +
                            "• <code>/town info</code> - информация о городе\n" +
                            "• <code>/pay &lt;ник&gt; &lt;сумма&gt;</code> - перевод денег");
        }
    }

    private boolean isAllowedCommand(String command) {
        String[] allowedCommands = {
                "/civ info",
                "/money",
                "/bal",
                "/balance",
                "/town info",
                "/pay"
        };

        String lowerCommand = command.toLowerCase();
        for (String allowed : allowedCommands) {
            if (lowerCommand.startsWith(allowed.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private void sendCommandToServer(Long telegramId, String commandId, String command,
                                     Long chatId, Integer threadId) {
        executorService.submit(() -> {
            try (Socket socket = new Socket(HOST, PORT);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

                socket.setSoTimeout(20000);

                out.println("player_command " + telegramId + " " + commandId + " " + command);
                out.flush();

                StringBuilder responseBuilder = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    responseBuilder.append(line).append("\n");

                    if (line.trim().isEmpty() ||
                            line.startsWith("COMMAND_RESULT:") ||
                            line.startsWith("ERROR:")) {
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException e) {
                            break;
                        }
                        if (!in.ready()) {
                            break;
                        }
                    }
                }

                String response = responseBuilder.toString().trim();

                if (!response.isEmpty()) {
                    processResponse(response, chatId, threadId, command);
                } else {
                    messageService.sendMessageHtml(chatId, threadId,
                            "❌ <b>Нет ответа от сервера!</b>");
                }
            } catch (Exception e) {
                e.printStackTrace();
                messageService.sendMessageHtml(chatId, threadId,
                        "❌ <b>Ошибка соединения с сервером!</b>\n" +
                                "Попробуйте позже.");
            }
        });
    }

    private void processResponse(String response, Long chatId, Integer threadId, String command) {
        if (response.startsWith("COMMAND_RESULT:")) {
            String[] parts = response.split(":", 4);
            if (parts.length >= 4) {
                String status = parts[2];
                String result = parts[3];

                if ("SUCCESS".equals(status)) {
                    String formatted = formatResult(command, result);
                    messageService.sendMessageHtml(chatId, threadId, formatted);
                } else {
                    messageService.sendMessageHtml(chatId, threadId,
                            "❌ <b>Ошибка:</b> " + escapeHtml(result));
                }
            }
        } else if (response.startsWith("ERROR:")) {
            messageService.sendMessageHtml(chatId, threadId,
                    "❌ <b>Ошибка сервера:</b> " + escapeHtml(response.substring(6)));
        }
    }

    private String formatResult(String command, String result) {
        switch (command.split(" ")[0].toLowerCase()) {
            case "/civ":
                if (command.contains("info")) {
                    return "<b>🏛️ Информация о цивилизации</b>\n\n" + formatCivInfo(result);
                }
                break;
            case "/money":
            case "/bal":
            case "/balance":
                return "\n\n" + escapeHtml(result);
            case "/town":
                return "<b>🏘️ Информация о городе</b>\n\n" + formatTownInfo(result);
            case "/pay":
                return formatPayResult(result);
            default:
                return "<b>📋 Результат команды</b>\n\n<pre>" + escapeHtml(result) + "</pre>";
        }
        return "<pre>" + escapeHtml(result) + "</pre>";
    }

    private String formatPayResult(String result) {
        StringBuilder response = new StringBuilder();
        response.append("<b>💸 Перевод денег</b>\n\n");

        if (result.startsWith("❌")) {
            response.append(escapeHtml(result));
        } else if (result.startsWith("✅")) {
            String formatted = escapeHtml(result.substring(2));
            response.append("✅ ").append("<b>").append(formatted).append("</b>");
        } else {
            response.append(escapeHtml(result));
        }

        return response.toString();
    }

    private String formatTownInfo(String result) {
        if (result.startsWith("❌") || result.startsWith("🏘️")) {
            return "<b>🏘️ Информация о городе</b>\n\n" + escapeHtml(result);
        }

        StringBuilder formatted = new StringBuilder();

        String[] lines = result.split("\n");
        for (String line : lines) {
            line = escapeHtml(line);

            if (line.contains("-----------[") && line.contains("]-----------")) {
                formatted.append("<b>").append(line).append("</b>\n");
            } else if (line.contains("Цивилизация:")) {
                formatted.append("🏛️ ").append(line).append("\n");
            } else if (line.contains("Статус города:")) {
                formatted.append("🏷️ ").append(line).append("\n");
            } else if (line.contains("Уровень Города:")) {
                formatted.append("📊 ").append(line).append("\n");
            } else if (line.contains("Мэры:")) {
                formatted.append("👑 ").append(line).append("\n");
            } else if (line.contains("Помощники:")) {
                formatted.append("🤝 ").append(line).append("\n");
            } else if (line.contains("Участки:") && line.contains("Слоты для построек:")) {
                formatted.append("📐 ").append(line).append("\n");
            } else if (line.contains("Чудеса Света:")) {
                formatted.append("🏛️ ").append(line).append("\n");
            } else if (line.contains("Растительность:") || line.contains("Молоточки:") || line.contains("Наука:")) {
                formatted.append("📈 ").append(line).append("\n");
            } else if (line.contains("Участники")) {
                formatted.append("👥 ").append(line).append("\n");
            } else if (line.contains("Счастье:")) {
                formatted.append("😊 ").append(line).append("\n");
            } else if (line.contains("Культура:")) {
                formatted.append("🎨 ").append(line).append("\n");
            } else if (line.contains("Казна:") || line.contains("Содержание города:")) {
                formatted.append("💰 ").append(line).append("\n");
            } else if (line.contains("Процентная ставка:") || line.contains("Пошлина:")) {
                formatted.append("🏦 ").append(line).append("\n");
            } else if (line.contains("Долг города:")) {
                formatted.append("💸 ").append(line).append("\n");
            } else if (line.contains("столицей цивилизации")) {
                formatted.append("👑 ").append(line).append("\n");
            } else if (line.contains("стремится к родной цивилизации")) {
                formatted.append("📯 ").append(line).append("\n");
            } else if (line.contains("Координаты ратуши:")) {
                formatted.append("📍 ").append(line).append("\n");
            } else {
                formatted.append(line).append("\n");
            }
        }

        return formatted.toString();
    }

    private String formatCivInfo(String result) {
        if (result.startsWith("-----------[ Цивилизация - ")) {
            String[] lines = result.split("\n");
            StringBuilder formatted = new StringBuilder();

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                line = escapeHtml(line);

                if (i == 0) {
                    formatted.append("<b>").append(line).append("</b>\n");
                } else if (line.contains("Счёт:") || line.contains("Города:") ||
                        line.contains("Тэг:") || line.contains("Основатель:")) {
                    formatted.append("<b>").append(line).append("</b>\n");
                } else if (line.startsWith("Золотой век:")) {
                    formatted.append("✨ ").append(line).append("\n");
                } else if (line.startsWith("Лидеры:") || line.startsWith("Советники:")) {
                    formatted.append("👑 ").append(line).append("\n");
                } else if (line.contains("Входящий подоходный налог:") ||
                        line.contains("Процент подоходного налога на науку:")) {
                    formatted.append("💰 ").append(line).append("\n");
                } else if (line.startsWith("Наука:")) {
                    formatted.append("🔬 ").append(line).append("\n");
                } else if (line.startsWith("Казна:")) {
                    formatted.append("🏦 ").append(line).append("\n");
                } else if (line.startsWith("Города:")) {
                    formatted.append("🏙️ ").append(line).append("\n");
                } else if (line.startsWith("Войны:")) {
                    formatted.append("⚔️ ").append(line).append("\n");
                } else {
                    formatted.append(line).append("\n");
                }
            }

            return formatted.toString();
        } else {
            return "<pre>" + escapeHtml(result) + "</pre>";
        }
    }

    private String escapeHtml(String text) {
        return MessageService.escapeHtml(text);
    }
}
