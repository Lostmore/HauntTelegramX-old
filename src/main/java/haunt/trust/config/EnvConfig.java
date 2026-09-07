package haunt.trust.config;

import io.github.cdimascio.dotenv.Dotenv;
import java.io.File;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class EnvConfig {
    private static final Dotenv dotenv;

    static {
        String jarDir = getJarDirectory();
        System.out.println("Ищем .env.development в папке с JAR: " + jarDir);

        Dotenv loadedEnv = Dotenv.configure()
                .directory(jarDir)
                .filename(".env.development")
                .ignoreIfMissing()
                .load();

        if (!hasTokens(loadedEnv)) {
            System.out.println("⚠️ .env.development не найден или пуст, пробуем .env...");

            Dotenv fallbackEnv = Dotenv.configure()
                    .directory(jarDir)
                    .filename(".env")
                    .ignoreIfMissing()
                    .load();

            if (hasTokens(fallbackEnv)) {
                loadedEnv = fallbackEnv;
            } else {
                System.err.println("Не удалось загрузить токены ни из .env.development, ни из .env!");
            }
        }

        dotenv = loadedEnv;

        checkLoadedTokens();
    }

    private static boolean hasTokens(Dotenv env) {
        String botToken = env.get("TELEGRAM_BOT_TOKEN");
        String githubToken = env.get("GITHUB_ACCESS_TOKEN");
        return botToken != null && !botToken.isEmpty() &&
                githubToken != null && !githubToken.isEmpty();
    }

    private static String getJarDirectory() {
        try {
            String jarPath = EnvConfig.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .getPath();

            // Декодируем URL (убираем %20 и т.д.)
            jarPath = URLDecoder.decode(jarPath, StandardCharsets.UTF_8);

            File jarFile = new File(jarPath);

            if (jarFile.exists() && jarFile.isFile() && jarPath.toLowerCase().endsWith(".jar")) {
                String parentDir = jarFile.getParent();
                System.out.println("Папка с JAR: " + parentDir);
                return parentDir;
            }

            throw new RuntimeException("Не могу определить папку с JAR. jarPath: " + jarPath);

        } catch (Exception e) {
            System.err.println("Ошибка определения папки JAR: " + e.getMessage());
            throw new RuntimeException("Невозможно определить расположение .env файла", e);
        }
    }

    private static void checkLoadedTokens() {
        System.out.println("=== ПРОВЕРКА ЗАГРУЗКИ ТОКЕНОВ ===");

        try {
            String botToken = getTelegramBotToken();
            System.out.println("✅ Telegram токен загружен: " +
                    botToken.substring(0, Math.min(10, botToken.length())) + "...");
        } catch (Exception e) {
            System.err.println("❌ Telegram токен НЕ загружен!");
            System.err.println("   Убедитесь что файл .env.development или .env находится в папке с JAR");
        }

        try {
            String githubToken = getGitHubAccessToken();
            System.out.println("✅ GitHub токен загружен: " +
                    githubToken.substring(0, Math.min(10, githubToken.length())) + "...");
        } catch (Exception e) {
            System.err.println("❌ GitHub токен НЕ загружен!");
        }

        String gitlabToken = getGitLabAccessToken();
        if (gitlabToken != null && !gitlabToken.isEmpty()) {
            System.out.println("✅ GitLab токен загружен: " +
                    gitlabToken.substring(0, Math.min(10, gitlabToken.length())) + "...");
        } else {
            System.out.println("⚠️ GitLab токен не загружен (возможно не нужен)");
        }

        System.out.println("==============================");
    }

    // Telegram
    public static String getTelegramBotToken() {
        String token = dotenv.get("TELEGRAM_BOT_TOKEN");
        if (token == null || token.isEmpty()) {
            throw new IllegalStateException("TELEGRAM_BOT_TOKEN не найден в .env файле!");
        }
        return token;
    }

    public static Long getTelegramChatId() {
        String chatId = dotenv.get("TELEGRAM_CHAT_ID");
        if (chatId == null || chatId.isEmpty()) {
            System.err.println("⚠️ TELEGRAM_CHAT_ID не найден, используем значение по умолчанию");
            return -1001904947967L;
        }
        return Long.parseLong(chatId);
    }

    public static Integer getTelegramCommitThreadId() {
        String threadId = dotenv.get("TELEGRAM_COMMIT_THREAD_ID");
        if (threadId == null || threadId.isEmpty()) {
            System.err.println("⚠️ TELEGRAM_COMMIT_THREAD_ID не найден, используем значение по умолчанию");
            return 77177;
        }
        return Integer.parseInt(threadId);
    }

    public static String getTelegramGitHubEmojiId() {
        return dotenv.get("TELEGRAM_GITHUB_EMOJI_ID", "");
    }

    public static String getTelegramGitLabEmojiId() {
        return dotenv.get("TELEGRAM_GITLAB_EMOJI_ID", "");
    }

    public static String getTelegramMergeEmojiId() {
        return dotenv.get("TELEGRAM_MERGE_EMOJI_ID", "");
    }

    // GitHub
    public static String getGitHubAccessToken() {
        String token = dotenv.get("GITHUB_ACCESS_TOKEN");
        if (token == null || token.isEmpty()) {
            throw new IllegalStateException("GITHUB_ACCESS_TOKEN не найден в .env файле!");
        }
        return token;
    }

    public static String getGitHubUrl() {
        return dotenv.get("GITHUB_URL", "https://api.github.com");
    }

    // GitLab
    public static String getGitLabAccessToken() {
        return dotenv.get("GITLAB_ACCESS_TOKEN", "");
    }

    public static String getGitLabUrl() {
        return dotenv.get("GITLAB_URL", "https://gitlab.com/api/v4");
    }

    public static String getGitLabProjectId() {
        return dotenv.get("GITLAB_PROJECT_ID", "");
    }

    public static String getGitLabWebhookSecret() {
        return dotenv.get("GITLAB_WEBHOOK_SECRET", "");
    }

    public static String getGitLabWebhookSigningToken() {
        return dotenv.get("GITLAB_WEBHOOK_SIGNING_TOKEN", "");
    }

    public static int getGitLabWebhookPort() {
        return Integer.parseInt(dotenv.get("GITLAB_WEBHOOK_PORT", "8081"));
    }

    public static String getGitLabWebhookPath() {
        String path = dotenv.get("GITLAB_WEBHOOK_PATH", "/hooks/haunttelegramx/gitlab");
        return path.startsWith("/") ? path : "/" + path;
    }

    public static boolean isGitLabPollingEnabled() {
        return Boolean.parseBoolean(dotenv.get("GITLAB_POLLING_ENABLED", "true"));
    }

    // Настройки
    public static long getPollingInterval() {
        String interval = dotenv.get("POLLING_INTERVAL_MS");
        if (interval == null || interval.isEmpty()) {
            return 180000L;
        }
        return Long.parseLong(interval);
    }

    public static String getStateFile() {
        return dotenv.get("STATE_FILE", "github_state.json");
    }
}
