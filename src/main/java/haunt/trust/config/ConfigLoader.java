package haunt.trust.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

public class ConfigLoader {

    public static BotConfig config;
    private static final String CONFIG_FILE_PATH = "config.yml";

    public static BotConfig getInstance() {
        if (config == null) {
            config = loadConfig();
        }
        return config;
    }

    public static BotConfig loadConfig() {
        if (config == null) {
            try {
                Yaml yaml = new Yaml();
                File file = new File(CONFIG_FILE_PATH);
                if (!file.exists()) {
                    throw new RuntimeException("Файл config.yml не найден рядом с JAR.");
                }

                try (InputStream inputStream = new FileInputStream(file)) {
                    config = yaml.loadAs(inputStream, BotConfig.class);
                }

                System.out.println("Загруженные авторизованные пользователи: " + config.getAutorized_users());
                System.out.println("Параметры базы данных:");
                System.out.println("Hostname: " + config.getGlobal_database().getHostname());
                System.out.println("Port: " + config.getGlobal_database().getPort());
                System.out.println("Username: " + config.getGlobal_database().getUsername());
                System.out.println("Database: " + config.getGlobal_database().getDatabase());
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException("Ошибка при загрузке config.yml");
            }
        }
        return config;
    }

    public static void saveConfig() {
        try {
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);

            Yaml yaml = new Yaml(options);

            FileWriter writer = new FileWriter(new File(CONFIG_FILE_PATH));
            Map<String, Object> data = new HashMap<>();
            data.put("autorized_users", config.getAutorized_users());
            data.put("support_users", config.getSupport_users());
            data.put("lost_connection_timer", config.getLost_connection_timer());

            Map<String, Object> database = new HashMap<>();
            database.put("hostname", config.getGlobal_database().getHostname());
            database.put("port", config.getGlobal_database().getPort());
            database.put("username", config.getGlobal_database().getUsername());
            database.put("password", config.getGlobal_database().getPassword());
            database.put("database", config.getGlobal_database().getDatabase());

            data.put("global_database", database);

            yaml.dump(data, writer);
            writer.close();
            System.out.println("Конфигурация успешно сохранена.");
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("Ошибка при сохранении config.yml");
        }
    }
}
