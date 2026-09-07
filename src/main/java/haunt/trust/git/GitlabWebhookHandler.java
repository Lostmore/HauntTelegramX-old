package haunt.trust.git;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import haunt.trust.config.EnvConfig;
import haunt.trust.utils.MessageService;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class GitlabWebhookHandler {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.of("Europe/Moscow"));

    private final Gson gson = new Gson();
    private final MessageService messages;
    private final long chatId;
    private final int threadId;
    private final String projectId;

    GitlabWebhookHandler(MessageService messages, long chatId, int threadId, String projectId) {
        this.messages = messages;
        this.chatId = chatId;
        this.threadId = threadId;
        this.projectId = projectId;
    }

    void handle(String event, String body) {
        JsonObject payload = gson.fromJson(body, JsonObject.class);
        if (payload == null || event == null) {
            throw new IllegalArgumentException("invalid payload");
        }
        if (!projectId.equals(string(object(payload, "project"), "id"))) {
            throw new IllegalArgumentException("wrong project");
        }

        switch (event) {
            case "Push Hook" -> push(payload);
            case "Merge Request Hook" -> mergeRequest(payload);
            case "Pipeline Hook" -> pipeline(payload);
            case "Release Hook" -> release(payload);
            default -> System.out.println("GitLab webhook ignored: " + event);
        }
    }

    private void push(JsonObject payload) {
        String branch = string(payload, "ref").replace("refs/heads/", "");
        JsonObject project = object(payload, "project");
        String projectName = projectName(project);
        String before = string(payload, "before");

        if (before.matches("0+")) {
            send(GitNotificationFormatter.branch(
                    "GitLab",
                    EnvConfig.getTelegramGitLabEmojiId(),
                    projectName,
                    branch,
                    DATE_FORMAT.format(Instant.now())
            ));
            return;
        }

        for (JsonElement element : array(payload, "commits")) {
            JsonObject commit = element.getAsJsonObject();
            String id = string(commit, "id");
            JsonObject author = object(commit, "author");
            send(GitNotificationFormatter.commit(
                    "GitLab",
                    EnvConfig.getTelegramGitLabEmojiId(),
                    projectName,
                    branch,
                    string(author, "name"),
                    id.substring(0, Math.min(8, id.length())),
                    formatDate(string(commit, "timestamp")),
                    string(commit, "message"),
                    string(commit, "url")
            ));
        }
    }

    private void mergeRequest(JsonObject payload) {
        JsonObject mr = object(payload, "object_attributes");
        JsonObject project = object(payload, "project");
        JsonObject user = object(payload, "user");
        String action = string(mr, "action");

        if ("open".equals(action)) {
            send(GitNotificationFormatter.changeRequestOpened(
                    "GitLab",
                    EnvConfig.getTelegramMergeEmojiId(),
                    "MR",
                    integer(mr, "iid"),
                    projectName(project),
                    string(mr, "title"),
                    string(user, "name"),
                    string(mr, "source_branch"),
                    string(mr, "target_branch"),
                    string(mr, "description"),
                    string(mr, "url")
            ));
            return;
        }

        String status = switch (action) {
            case "merge" -> "слит";
            case "close" -> "закрыт";
            case "reopen" -> "переоткрыт";
            default -> null;
        };
        if (status == null) {
            return;
        }

        String emoji = switch (action) {
            case "merge" -> "✅";
            case "close" -> "❌";
            default -> "🔄";
        };
        send(GitNotificationFormatter.changeRequestState(
                "GitLab",
                EnvConfig.getTelegramMergeEmojiId(),
                "MR",
                integer(mr, "iid"),
                projectName(project),
                string(mr, "title"),
                string(mr, "source_branch"),
                string(mr, "target_branch"),
                status,
                emoji,
                string(mr, "url")
        ));
    }

    private void pipeline(JsonObject payload) {
        JsonObject pipeline = object(payload, "object_attributes");
        JsonObject project = object(payload, "project");
        String status = string(pipeline, "status");
        String url = string(project, "web_url") + "/-/pipelines/" + string(pipeline, "id");
        send(String.format(
                "🦊 <b>GitLab · pipeline %s</b>\n<b>%s</b>\n\n🌿 <code>%s</code>\n👤 %s\n\n🔗 <a href=\"%s\">Открыть pipeline</a>",
                escape(status),
                escape(projectName(project)),
                escape(string(pipeline, "ref")),
                escape(string(object(payload, "user"), "name")),
                escape(url)
        ));
    }

    private void release(JsonObject payload) {
        JsonObject project = object(payload, "project");
        send(String.format(
                "🦊 <b>GitLab · новый release</b>\n<b>%s</b>\n\n<b>%s</b>  <code>%s</code>\n\n🔗 <a href=\"%s\">Открыть release</a>",
                escape(projectName(project)),
                escape(string(payload, "name")),
                escape(string(payload, "tag")),
                escape(string(payload, "url"))
        ));
    }

    private void send(String message) {
        messages.sendMessageHtml(chatId, threadId, message);
    }

    private static String projectName(JsonObject project) {
        String path = string(project, "path_with_namespace");
        return path.isBlank() ? string(project, "name") : path;
    }

    private static String formatDate(String value) {
        try {
            return DATE_FORMAT.format(Instant.parse(value));
        } catch (Exception ignored) {
            return value;
        }
    }

    private static JsonObject object(JsonObject parent, String name) {
        JsonElement value = parent.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray array(JsonObject parent, String name) {
        JsonElement value = parent.get(name);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static int integer(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? 0 : value.getAsInt();
    }

    private static String escape(String value) {
        return MessageService.escapeHtml(value);
    }
}
