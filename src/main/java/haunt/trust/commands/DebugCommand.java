package haunt.trust.commands;

import haunt.trust.utils.MessageService;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class DebugCommand {
    private final MessageService messageService;

    public DebugCommand(MessageService messageService) {
        this.messageService = messageService;
    }

    public void handleDebugCommand(Message msg, Long chatId, Integer threadId) {
        try {
            StringBuilder debugInfo = new StringBuilder();

            debugInfo.append("🔧 <b>DEBUG</b>\n\n");

            debugInfo.append("<b>Информация о чате:</b>\n");
            debugInfo.append("• <b>Chat ID:</b> <code>").append(chatId).append("</code>\n");
            debugInfo.append("• <b>Thread ID:</b> ").append(threadId != null ? "<code>" + threadId + "</code>" : "❌ <i>нет</i>").append("\n");
            debugInfo.append("• <b>Type:</b> ").append(getChatType(chatId)).append("\n\n");
            if (msg.getFrom() != null) {
                debugInfo.append("👤 <b>User info:</b>\n");
                debugInfo.append("• <b>User ID:</b> <code>").append(msg.getFrom().getId()).append("</code>\n");
                debugInfo.append("• <b>Username:</b> ").append(msg.getFrom().getUserName() != null ? "@" + msg.getFrom().getUserName() : "❌ <i>нет</i>").append("\n");
            }

            debugInfo.append("<b>Информация о сообщении:</b>\n");
            debugInfo.append("• <b>Message ID:</b> <code>").append(msg.getMessageId()).append("</code>\n");
            debugInfo.append("• <b>Text:</b> <code>").append(escapeHtml(msg.getText())).append("</code>\n");
            debugInfo.append("• <b>Enter data:</b> ").append(formatDate(msg.getDate())).append("\n");

            if (msg.getReplyToMessage() != null) {
                debugInfo.append("• <b>Reply to Message ID:</b> <code>").append(msg.getReplyToMessage().getMessageId()).append("</code>\n");
            }

            if (msg.getChat() != null) {
                debugInfo.append("\n<b>Детали:</b>\n");
                debugInfo.append("• <b>Name chat:</b> ").append(msg.getChat().getTitle() != null ? escapeHtml(msg.getChat().getTitle()) : "❌ <i>нет</i>").append("\n");
                debugInfo.append("• <b>Type chat:</b> ").append(msg.getChat().getType()).append("\n");

                if (msg.getChat().getUserName() != null) {
                    debugInfo.append("• <b>Username chat:</b> @").append(msg.getChat().getUserName()).append("\n");
                }
            }

            debugInfo.append("\n⚙️ <b>Entities и т.д:</b>\n");
            debugInfo.append("• <b>Timestamp message:</b> ").append(msg.getDate()).append("\n");
            debugInfo.append("• <b>Forwarded:</b> ").append(msg.getForwardFrom() != null ? "✅ да" : "❌ нет").append("\n");
            debugInfo.append("• <b>Есть entities:</b> ").append(msg.getEntities() != null ? "✅ " + msg.getEntities().size() + " шт." : "❌ нет").append("\n");

            if (msg.hasDocument()) {
                debugInfo.append("• <b>Document:</b> ✅ да\n");
            }
            if (msg.hasPhoto()) {
                debugInfo.append("• <b>Photo:</b> ✅ да\n");
            }
            if (msg.hasVideo()) {
                debugInfo.append("• <b>Video:</b> ✅ да\n");
            }

            debugInfo.append("\n📋 <b>Topped:</b>\n");
            debugInfo.append("• <code>").append(chatId).append("</code> - Chat ID\n");

            if (threadId != null) {
                debugInfo.append("• <code>").append(threadId).append("</code> - Thread ID\n");
            }
            debugInfo.append("• <code>").append(msg.getFrom().getId()).append("</code> - User ID\n");

            messageService.sendMessageHtml(chatId, threadId, debugInfo.toString());

        } catch (Exception e) {
            String errorMessage = "❌ <b>Ошибка при получении debug информации:</b>\n<code>" +
                    escapeHtml(e.getMessage()) + "</code>";
            messageService.sendMessageHtml(chatId, threadId, errorMessage);
        }
    }

    private String getChatType(Long chatId) {
        if (chatId > 0) {
            return "👤 Личный чат";
        } else if (chatId < 0 && chatId > -1000000000000L) {
            return "👥 Группа";
        } else if (chatId <= -1000000000000L) {
            return "📢 Супергруппа/Канал";
        } else {
            return "❓ Неизвестный тип";
        }
    }

    private String formatDate(Integer timestamp) {
        try {
            Instant instant = Instant.ofEpochSecond(timestamp);
            DateTimeFormatter formatter =
                    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.of("Europe/Moscow"));
            return formatter.format(instant);
        } catch (Exception e) {
            return "Ошибка формата: " + timestamp;
        }
    }

    private String escapeHtml(String text) {
        return text == null ? "null" : MessageService.escapeHtml(text);
    }

    public void handleRawDebugCommand(Message msg, Long chatId, Integer threadId) {
        try {
            StringBuilder rawInfo = new StringBuilder();
            rawInfo.append("📊 <b>RAW DEBUG</b> 📊\n\n");
            rawInfo.append("```json\n");
            rawInfo.append("{\n");
            rawInfo.append("  \"chat_id\": ").append(chatId).append(",\n");
            rawInfo.append("  \"thread_id\": ").append(threadId != null ? threadId : "null").append(",\n");
            rawInfo.append("  \"message_id\": ").append(msg.getMessageId()).append(",\n");

            if (msg.getFrom() != null) {
                rawInfo.append("  \"user\": {\n");
                rawInfo.append("    \"id\": ").append(msg.getFrom().getId()).append(",\n");
                rawInfo.append("    \"username\": \"").append(msg.getFrom().getUserName() != null ? msg.getFrom().getUserName() : "null").append("\",\n");
                rawInfo.append("    \"first_name\": \"").append(msg.getFrom().getFirstName()).append("\",\n");
                rawInfo.append("    \"language_code\": \"").append(msg.getFrom().getLanguageCode() != null ? msg.getFrom().getLanguageCode() : "null").append("\"\n");
                rawInfo.append("  },\n");
            }

            rawInfo.append("  \"text\": \"").append(escapeJson(msg.getText())).append("\",\n");
            rawInfo.append("  \"date\": ").append(msg.getDate()).append(",\n");
            rawInfo.append("  \"timestamp\": \"").append(Instant.now().getEpochSecond()).append("\"\n");
            rawInfo.append("}\n");
            rawInfo.append("```");

            messageService.sendMessageHtml(chatId, threadId, rawInfo.toString());

        } catch (Exception e) {
            messageService.sendMessageHtml(chatId, threadId, "❌ Ошибка при формировании RAW данных");
        }
    }

    private String escapeJson(String text) {
        if (text == null) return "null";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
