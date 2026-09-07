package haunt.trust.commands;

import haunt.trust.NettyServerMonitor;
import haunt.trust.database.SQL;
import haunt.trust.utils.MessageService;

import java.util.concurrent.TimeUnit;

public final class ClearCivCraftUsersCommand {
    private final MessageService messages;

    public ClearCivCraftUsersCommand(MessageService messages) {
        this.messages = messages;
    }

    public void handle(Long chatId, Integer threadId) {
        int unlinked = SQL.clearCivCraftUserBindings();
        String runtimeResponse;
        try {
            runtimeResponse = NettyServerMonitor.sendAuthCommand("auth_clear_runtime").get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            runtimeResponse = "ERROR:" + e.getMessage();
        }

        if (unlinked < 0) {
            messages.sendMessageHtml(chatId, threadId,
                    "❌ <b>Не удалось очистить привязки CivCraft-пользователей.</b>");
            return;
        }
        messages.sendMessageHtml(chatId, threadId,
                "😁 <b>Привязки CivCraft очищены.</b>\n" +
                        "Отвязано записей: <b>" + unlinked + "</b>\n" +
                        "Runtime cleared: <code>" + MessageService.escapeHtml(runtimeResponse) + "</code>\n\n" +
                        "Users have saved");
    }
}
