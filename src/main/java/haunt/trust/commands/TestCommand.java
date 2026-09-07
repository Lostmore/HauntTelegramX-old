package haunt.trust.commands;

import haunt.trust.database.SQL;
import haunt.trust.utils.MessageService;

import java.sql.*;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.util.*;

public class TestCommand {
    private MessageService messageService;

    public TestCommand(MessageService messageService) {
        this.messageService = messageService;
    }

    public void handleTestCommand(Long chatId, Integer threadId) {
        StringBuilder result = new StringBuilder();
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy");

        try {
            Connection conn = SQL.getConnection();
            String query = "SELECT * FROM GLOBAL_SESSIONS";
            try (PreparedStatement ps = conn.prepareStatement(query);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    int requestId = rs.getInt("request_id");
                    String key = rs.getString("key");
                    String value = rs.getString("value");
                    int civId = rs.getInt("civ_id");
                    int townId = rs.getInt("town_id");
                    int structId = rs.getInt("struct_id");
                    long time = rs.getLong("time");

                    String formattedDate = dateFormat.format(new Date(time));

                    String server = "неизвестно";
                    String player = "неизвестно";
                    if (key.contains(":")) {
                        String[] parts = key.split(":", 2);
                        server = parts[0];
                        player = parts[1];
                    }

                    String ore = "неизвестно";
                    String amount = "неизвестно";
                    if (value.contains(":")) {
                        String[] parts = value.split(":", 2);
                        ore = parts[0];
                        amount = parts[1];
                    }

                    result.append("Request ID: ").append(requestId).append("\n")
                            .append("Сервер: ").append(server).append("\n")
                            .append("Игрок: ").append(player).append("\n")
                            .append("Руда: ").append(ore).append("\n")
                            .append("Количество: ").append(amount).append("\n")
                            .append("Civ ID: ").append(civId).append("\n")
                            .append("Town ID: ").append(townId).append("\n")
                            .append("Struct ID: ").append(structId).append("\n")
                            .append("Дата: ").append(formattedDate).append("\n")
                            .append("----------------------\n");
                }

                if (result.length() > 0) {
                    messageService.sendMessage(chatId, threadId, result.toString());
                } else {
                    messageService.sendMessage(chatId, threadId, "❌ Нет записей в базе данных.");
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
            messageService.sendMessage(chatId, threadId, "❌ Произошла ошибка при выполнении запроса к базе данных.");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

}
