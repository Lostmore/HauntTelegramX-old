package haunt.trust;

import haunt.trust.config.ConfigLoader;
import haunt.trust.config.EnvConfig;
import haunt.trust.database.SQL;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Main {

    public static final String BUILD_DATE;

    static {
        BUILD_DATE = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss"));
    }

    public static void main(String[] args) {

        try {
            ConfigLoader.getInstance();
            SQL.initialize();
            TelegramBotsLongPollingApplication botsApplication = new TelegramBotsLongPollingApplication();

            final String botToken = EnvConfig.getTelegramBotToken();
            final TelegramClient telegramClient = new OkHttpTelegramClient(botToken);
            final Bot bot = new Bot(telegramClient);

            botsApplication.registerBot(botToken, new LongPollingSingleThreadUpdateConsumer() {
                @Override
                public void consume(Update update) {
                    bot.onUpdateReceived(update);
                }
            });

            System.out.println("Бот запущен ");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Завершаем работу...");
                try {
                    botsApplication.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }));

            while (true) {
                Thread.sleep(1000);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}