package haunt.trust.git;

import haunt.trust.utils.MessageService;

final class GitNotificationFormatter {

    private GitNotificationFormatter() {
    }

    static String commit(
            String provider,
            String providerEmoji,
            String project,
            String branch,
            String author,
            String hash,
            String date,
            String message,
            String url
    ) {
        return String.format(
                "%s <b>%s · новый commit</b>\n" +
                        "<b>%s</b>  <code>%s</code>\n\n" +
                        "<blockquote>%s</blockquote>\n\n" +
                        "👤 %s  ·  <code>%s</code>\n" +
                        "🕒 %s\n\n" +
                        "🔗 <a href=\"%s\">Открыть commit</a>",
                customEmoji(providerEmoji, provider.equals("GitHub") ? "🐙" : "🦊"),
                provider,
                escape(project),
                escape(branch),
                escape(trim(message, 700)),
                escape(author),
                escape(hash),
                escape(date),
                escapeAttribute(url)
        );
    }

    static String branch(
            String provider,
            String providerEmoji,
            String project,
            String branch,
            String date
    ) {
        return String.format(
                "%s <b>%s · новая ветка</b>\n" +
                        "<b>%s</b>\n\n" +
                        "🌿 <code>%s</code>\n" +
                        "🕒 %s",
                customEmoji(providerEmoji, provider.equals("GitHub") ? "🐙" : "🦊"),
                provider,
                escape(project),
                escape(branch),
                escape(date)
        );
    }

    static String changeRequestOpened(
            String provider,
            String providerEmoji,
            String kind,
            int number,
            String project,
            String title,
            String author,
            String sourceBranch,
            String targetBranch,
            String description,
            String url
    ) {
        return String.format(
                "%s <b>%s · новый %s #%d</b>\n" +
                        "<b>%s</b>\n\n" +
                        "<b>%s</b>\n" +
                        "👤 %s\n" +
                        "🌿 <code>%s</code> → <code>%s</code>\n\n" +
                        "<blockquote>%s</blockquote>\n\n" +
                        "🔗 <a href=\"%s\">Открыть %s</a>",
                customEmoji(providerEmoji, provider.equals("GitHub") ? "🔀" : "🦊"),
                provider,
                kind,
                number,
                escape(project),
                escape(title),
                escape(author),
                escape(sourceBranch),
                escape(targetBranch),
                escape(trim(description, 500)),
                escapeAttribute(url),
                kind
        );
    }

    static String changeRequestState(
            String provider,
            String providerEmoji,
            String kind,
            int number,
            String project,
            String title,
            String sourceBranch,
            String targetBranch,
            String status,
            String statusEmoji,
            String url
    ) {
        return String.format(
                "%s <b>%s · %s #%d %s</b>\n" +
                        "<b>%s</b>\n\n" +
                        "<b>%s</b>\n" +
                        "🌿 <code>%s</code> → <code>%s</code>\n" +
                        "%s <b>%s</b>\n\n" +
                        "🔗 <a href=\"%s\">Открыть %s</a>",
                customEmoji(providerEmoji, provider.equals("GitHub") ? "🔀" : "🦊"),
                provider,
                kind,
                number,
                escape(status.toLowerCase()),
                escape(project),
                escape(title),
                escape(sourceBranch),
                escape(targetBranch),
                statusEmoji,
                escape(status),
                escapeAttribute(url),
                kind
        );
    }

    private static String customEmoji(String emojiId, String fallback) {
        if (emojiId == null || emojiId.isBlank()) {
            return fallback;
        }
        return "<tg-emoji emoji-id=\"" + escapeAttribute(emojiId) + "\">" + fallback + "</tg-emoji>";
    }

    private static String trim(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "Без описания";
        }

        String normalized = value.trim().replace("\r\n", "\n");
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength - 1) + "…";
    }

    private static String escape(String value) {
        return MessageService.escapeHtml(value);
    }

    private static String escapeAttribute(String value) {
        return MessageService.escapeHtml(value == null ? "" : value);
    }
}
