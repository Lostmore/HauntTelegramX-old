package haunt.trust.commands;

import haunt.trust.git.Github;
import haunt.trust.utils.MessageService;

public class GithubStatusCommand {
    private final Github github;
    private final MessageService messageService;

    private final long githubChatId = -1001904947967L;
    private final int githubThreadId = 77177;

    public GithubStatusCommand(Github github, MessageService messageService) {
        this.github = github;
        this.messageService = messageService;
    }

    public void handleGithubStatusCommand(String text, Long chatId, Integer threadId) {
        if (chatId != githubChatId || threadId == null || threadId != githubThreadId) {
            messageService.sendMessageHtml(chatId, threadId, "❌ Github команды доступны в ТЕХ.ЧАТЕ.");
            return;
        }

        if (text.startsWith("/github_status")) {
            github.sendStatusReport();
        } else if (text.startsWith("/github_help")) {
            sendHelpMessage(chatId, threadId);
        }
    }

    private void sendHelpMessage(Long chatId, Integer threadId) {
        String helpText =
                "🔧 <b>Github Команды</b>\n\n" +
                        "📊 <code>/github_status</code> - Статус мониторинга github\n" +
                        "ℹ️ <code>/github_help</code> - Эта справка\n\n" +
                        "🌿 <b>Автоматически отслеживает:</b>\n" +
                        "- Новые коммиты во всех ветках\n" +
                        "- Создание новых веток\n" +
                        "- Обновления в существующих ветках";

        messageService.sendMessageHtml(chatId, threadId, helpText);
    }
}
