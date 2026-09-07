package haunt.trust.commands;

import haunt.trust.git.Gitlab;
import haunt.trust.utils.MessageService;

public class GitlabStatusCommand {
    private final Gitlab gitlab;
    private final MessageService messageService;

    private final long gitlabChatId = -1001904947967L;
    private final int gitlabThreadId = 77177;

    public GitlabStatusCommand(Gitlab gitlab, MessageService messageService) {
        this.gitlab = gitlab;
        this.messageService = messageService;
    }

    public void handleGitlabStatusCommand(String text, Long chatId, Integer threadId) {
        if (chatId != gitlabChatId || threadId == null || threadId != gitlabThreadId) {
            messageService.sendMessageHtml(chatId, threadId, "❌ GitLab команды доступны в ТЕХ.ЧАТЕ.");
            return;
        }

        if (text.startsWith("/gitlab_status")) {
            gitlab.sendStatusReport();
        } else if (text.startsWith("/gitlab_help")) {
            sendHelpMessage(chatId, threadId);
        }
    }

    private void sendHelpMessage(Long chatId, Integer threadId) {
        String helpText =
                "🔧 <b>GitLab Команды</b>\n\n" +
                        "📊 <code>/gitlab_status</code> - Статус мониторинга GitLab\n" +
                        "ℹ️ <code>/gitlab_help</code> - Эта справка\n\n" +
                        "🌿 <b>Автоматически отслеживает:</b>\n" +
                        "- Новые коммиты во всех ветках\n" +
                        "- Создание новых веток\n" +
                        "- Обновления в существующих ветках";

        messageService.sendMessageHtml(chatId, threadId, helpText);
    }
}
