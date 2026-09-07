package haunt.trust;

import haunt.trust.database.SQL;
import haunt.trust.utils.MessageService;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import lombok.Getter;
import lombok.Setter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Getter
@Setter
public class NettyServerMonitor {

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private String[] hosts = {"localhost"};
    private int[] ports = {25422}; // нужные порты для подключения
    private int[] threadIds = {6};
    private String[] serverNames = {"Taurus"};
    private final int maxRetries = 3;
    private final long retryDelayMillis = 300000; // 5 мин
    private final boolean[] serverStatus = new boolean[hosts.length];
    private final int[] retryCounts = new int[hosts.length];
    private final long[] lastFailedTime = new long[hosts.length];
    private final long[] lastAlertPollTime = new long[hosts.length];
    private final boolean[] notificationSent = new boolean[hosts.length];
    private final Bot bot;
    private final EventLoopGroup group = new NioEventLoopGroup();
    private AtomicBoolean[] isRetrying;
    private final MessageService messageService;

    public NettyServerMonitor(Bot bot) {
        this.bot = bot;
        this.messageService = new MessageService(bot);
        this.isRetrying = new AtomicBoolean[hosts.length];

        for (int i = 0; i < hosts.length; i++) {
            isRetrying[i] = new AtomicBoolean(false);
        }
    }

    public void startMonitoring() {
        for (int i = 0; i < hosts.length; i++) {
            final int index = i;
            sendTelegramMessage("🔄 Начинаем проверку подключения к серверу: `" + serverNames[index] + "`", threadIds[index]);
            checkServer(index);
        }

        for (int i = 0; i < hosts.length; i++) {
            final int index = i;
            scheduler.scheduleAtFixedRate(() -> checkServer(index), 0, 60, TimeUnit.SECONDS);
            scheduler.scheduleAtFixedRate(() -> pollAlerts(index), 10, 15, TimeUnit.SECONDS);
            scheduler.scheduleAtFixedRate(() -> pollNotifications(index), 10, 15, TimeUnit.SECONDS);
        }
    }

    private void pollAlerts(int index) {
        long now = System.currentTimeMillis();
        if (now - lastAlertPollTime[index] < 10_000L) {
            return;
        }
        lastAlertPollTime[index] = now;

        getServerAlerts(index).thenAccept(alerts -> {
            for (String alert : alerts) {
                messageService.sendMessageMarkdownV2Raw(
                        -1002521750905L,
                        resolveAlertThreadId(alert),
                        "🚨 *" + escapeMarkdownV2(serverNames[index]) + "*\n```java\n" + escapeMarkdownV2Code(alert) + "\n```"
                );
            }
        }).exceptionally(error -> null);
    }

    private void pollNotifications(int index) {
        // use the same timer logic for simplicity, or we can use lastAlertPollTime for both if we want, but let's use it
        getServerNotifications(index).thenAccept(notifications -> {
            for (String alert : notifications) {
                if (alert.startsWith("DM|")) {
                    String[] parts = alert.split("\\|", 4);
                    if (parts.length >= 4) {
                        try {
                            long telegramId = Long.parseLong(parts[1]);
                            String category = parts[2];
                            String text = parts[3];

                            String setting = "notify_" + category.toLowerCase();
                            if (SQL.isSettingEnabled(telegramId, setting)) {
                                messageService.sendMessage(telegramId, null, text);
                            }
                        } catch (Exception e) {
                            System.err.println("Failed to parse DM alert: " + alert);
                        }
                    }
                    continue;
                }

                if (alert.startsWith("BROADCAST|")) {
                    String[] parts = alert.split("\\|", 3);
                    if (parts.length >= 3) {
                        String category = parts[1];
                        String text = parts[2];
                        String setting = "notify_" + category.toLowerCase();

                        try (java.sql.Connection conn = SQL.getConnection();
                             java.sql.PreparedStatement ps = conn.prepareStatement(
                                     "SELECT telegram_id FROM " + SQL.GLOBAL_AUTHORIZED_USERS_TABLE + " WHERE " + setting + " = TRUE")) {
                            
                            try (java.sql.ResultSet rs = ps.executeQuery()) {
                                while (rs.next()) {
                                    long telegramId = rs.getLong("telegram_id");
                                    messageService.sendMessage(telegramId, null, text);
                                }
                            }
                        } catch (Exception e) {
                            System.err.println("Failed to process BROADCAST alert: " + alert);
                        }
                    }
                    continue;
                }
            }
        }).exceptionally(error -> null);
    }

    private int resolveAlertThreadId(String alert) {
        if (alert == null) {
            return 3;
        }

        for (String line : alert.split("\\R")) {
            String trimmed = line.trim();
            int threadIndex = trimmed.indexOf("Thread:");
            if (threadIndex == -1) {
                continue;
            }

            try {
                return Integer.parseInt(trimmed.substring(threadIndex + "Thread:".length()).trim());
            } catch (NumberFormatException ignored) {
                return 3;
            }
        }

        if (alert.contains("Wiki report")) {
            return 8298;
        }
        return 3;
    }

    private void checkServer(int index) {
        if (isRetrying[index].get()) return;

        isRetrying[index].set(true);

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new LineBasedFrameDecoder(1024));
                        pipeline.addLast(new StringDecoder(StandardCharsets.UTF_8));
                        pipeline.addLast(new StringEncoder(StandardCharsets.UTF_8));
                        pipeline.addLast(new SimpleChannelInboundHandler<String>() {
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                ctx.writeAndFlush("ping\n");
                            }

                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, String msg) {
                                msg = msg.trim();
                                if ("pong".equalsIgnoreCase(msg)) {
                                    handleSuccess(index);
                                } else {
                                    System.out.println("⚠️ Сервер дал странный ответ: " + msg);
                                }
                                ctx.close();
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                System.err.println("Ошибка при подключении: " + cause.getMessage());
                                ctx.close();
                                handleFailure(index, cause.getMessage());
                            }
                        });
                    }
                });

        bootstrap.connect(hosts[index], ports[index]).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                handleFailure(index, future.cause().getMessage());
            }
            isRetrying[index].set(false);
        });
    }

    public CompletableFuture<String> getServerStats(int index) {
        CompletableFuture<String> future = new CompletableFuture<>();

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        // Увеличиваем максимальную длину фрейма
                        pipeline.addLast(new LineBasedFrameDecoder(512));
                        pipeline.addLast(new StringDecoder(StandardCharsets.UTF_8));
                        pipeline.addLast(new StringEncoder(StandardCharsets.UTF_8));
                        pipeline.addLast(new SimpleChannelInboundHandler<String>() {
                            private final StringBuilder responseBuilder = new StringBuilder();

                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                ctx.writeAndFlush("stats\n");
                            }

                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, String msg) {
                                msg = msg.trim();
                                if (msg.startsWith("online:") || msg.startsWith("tps:") || msg.startsWith("uptime:")) {
                                    responseBuilder.append(msg).append("\n");

                                    if (responseBuilder.toString().split("\n").length >= 3) {
                                        String[] parts = responseBuilder.toString().split("\n");
                                        String online = parts[0].substring("online:".length()).trim();
                                        String tps = parts[1].substring("tps:".length()).trim();
                                        String uptime = parts[2].substring("uptime:".length()).trim();

                                        String formattedResponse = String.format(
                                                "✅ Онлайн: %s\n✅ TPS: %s\n✅ Аптайм: %s",
                                                online, tps, uptime
                                        );
                                        future.complete(formattedResponse);
                                        ctx.close();
                                    }
                                }
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                future.complete("❌ Ошибка: " + cause.getMessage());
                                ctx.close();
                            }
                        });
                    }
                });

        bootstrap.connect(hosts[index], ports[index]).addListener((ChannelFutureListener) connectFuture -> {
            if (!connectFuture.isSuccess()) {
                future.complete("❌ Не удалось подключиться: " + connectFuture.cause().getMessage());
            }
        });

        return future;
    }

    public CompletableFuture<List<String>> getServerAlerts(int index) {
        CompletableFuture<List<String>> future = new CompletableFuture<>();

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new LineBasedFrameDecoder(8192));
                        pipeline.addLast(new StringDecoder(StandardCharsets.UTF_8));
                        pipeline.addLast(new StringEncoder(StandardCharsets.UTF_8));
                        pipeline.addLast(new SimpleChannelInboundHandler<String>() {
                            private final List<String> alerts = new ArrayList<>();

                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                ctx.writeAndFlush("alerts\n");
                            }

                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, String msg) {
                                msg = msg.trim();
                                if (msg.isEmpty() || "NO_ALERTS".equalsIgnoreCase(msg)) {
                                    future.complete(alerts);
                                    ctx.close();
                                    return;
                                }

                                if (msg.startsWith("ALERT:")) {
                                    String encoded = msg.substring("ALERT:".length());
                                    try {
                                        alerts.add(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
                                    } catch (IllegalArgumentException ignored) {
                                    }
                                }
                            }

                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) {
                                if (!future.isDone()) {
                                    future.complete(alerts);
                                }
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                future.completeExceptionally(cause);
                                ctx.close();
                            }
                        });
                    }
                });

        bootstrap.connect(hosts[index], ports[index]).addListener((ChannelFutureListener) connectFuture -> {
            if (!connectFuture.isSuccess()) {
                future.completeExceptionally(connectFuture.cause());
            }
        });

        return future;
    }

    public CompletableFuture<List<String>> getServerNotifications(int index) {
        CompletableFuture<List<String>> future = new CompletableFuture<>();

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new LineBasedFrameDecoder(8192));
                        pipeline.addLast(new StringDecoder(StandardCharsets.UTF_8));
                        pipeline.addLast(new StringEncoder(StandardCharsets.UTF_8));
                        pipeline.addLast(new SimpleChannelInboundHandler<String>() {
                            private final List<String> notifications = new ArrayList<>();

                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                ctx.writeAndFlush("notifications\n");
                            }

                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, String msg) {
                                msg = msg.trim();
                                if (msg.isEmpty() || "NO_NOTIFICATIONS".equalsIgnoreCase(msg)) {
                                    future.complete(notifications);
                                    ctx.close();
                                    return;
                                }

                                if (msg.startsWith("NOTIF:")) {
                                    String encoded = msg.substring("NOTIF:".length());
                                    try {
                                        notifications.add(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
                                    } catch (IllegalArgumentException ignored) {
                                    }
                                }
                            }

                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) {
                                if (!future.isDone()) {
                                    future.complete(notifications);
                                }
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                future.completeExceptionally(cause);
                                ctx.close();
                            }
                        });
                    }
                });

        bootstrap.connect(hosts[index], ports[index]).addListener((ChannelFutureListener) connectFuture -> {
            if (!connectFuture.isSuccess()) {
                future.completeExceptionally(connectFuture.cause());
            }
        });

        return future;
    }

    public CompletableFuture<String> checkPlayer(int index, String playerName) {
        return checkPlayer(index, playerName, false);
    }

    public CompletableFuture<String> checkPlayerFull(int index, String playerName) {
        return checkPlayer(index, playerName, true);
    }

    private CompletableFuture<String> checkPlayer(int index, String playerName, boolean fullIp) {
        CompletableFuture<String> future = new CompletableFuture<>();

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new LineBasedFrameDecoder(8192));
                        pipeline.addLast(new StringDecoder(StandardCharsets.UTF_8));
                        pipeline.addLast(new StringEncoder(StandardCharsets.UTF_8));
                        pipeline.addLast(new SimpleChannelInboundHandler<String>() {
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                ctx.writeAndFlush((fullIp ? "check_player_full " : "check_player ") + playerName + "\n");
                            }

                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, String msg) {
                                msg = msg.trim();
                                if (msg.startsWith("CHECK:")) {
                                    String encoded = msg.substring("CHECK:".length());
                                    try {
                                        future.complete(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
                                    } catch (IllegalArgumentException e) {
                                        future.complete("ERROR:Invalid_response");
                                    }
                                } else {
                                    future.complete(msg);
                                }
                                ctx.close();
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                future.completeExceptionally(cause);
                                ctx.close();
                            }
                        });
                    }
                });

        bootstrap.connect(hosts[index], ports[index]).addListener((ChannelFutureListener) connectFuture -> {
            if (!connectFuture.isSuccess()) {
                future.completeExceptionally(connectFuture.cause());
            }
        });

        return future;
    }

    public void sendCommandToServer(String host, int port, String command) {
        if (command.startsWith("/cmd ")) {
            command = command.substring(5).trim();
        }

        //System.out.println("Отправляем команду на сервер: " + command);

        // обработка команды
        final String finalCommand = command;
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new LineBasedFrameDecoder(1024));
                        pipeline.addLast(new StringDecoder(StandardCharsets.UTF_8));
                        pipeline.addLast(new StringEncoder(StandardCharsets.UTF_8));
                        pipeline.addLast(new SimpleChannelInboundHandler<String>() {
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                ctx.writeAndFlush(finalCommand + "\n");
                                ctx.close();
                            }

                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, String msg) {

                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                System.err.println("Ошибка при отправке команды: " + cause.getMessage());
                                ctx.close();
                            }
                        });
                    }
                });

        bootstrap.connect(host, port).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                System.err.println("❌ Не удалось отправить команду на " + host + ":" + port + ": " + future.cause().getMessage());
            }
        });
    }

    public static CompletableFuture<String> sendAuthVerifyRequest(String code, long telegramId) {
        CompletableFuture<String> future = new CompletableFuture<>();

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(new NioEventLoopGroup(1))
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new LineBasedFrameDecoder(1024));
                        pipeline.addLast(new StringDecoder(StandardCharsets.UTF_8));
                        pipeline.addLast(new StringEncoder(StandardCharsets.UTF_8));
                        pipeline.addLast(new SimpleChannelInboundHandler<String>() {
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                ctx.writeAndFlush("auth_verify " + code + " " + telegramId + "\n");
                            }

                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, String msg) {
                                future.complete(msg.trim());
                                ctx.close();
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                future.completeExceptionally(cause);
                                ctx.close();
                            }
                        });
                    }
                });

        bootstrap.connect("127.0.0.1", 25422).addListener((ChannelFutureListener) future1 -> {
            if (!future1.isSuccess()) {
                future.completeExceptionally(future1.cause());
            }
        });

        return future;
    }


    public static CompletableFuture<String> sendAuthUnlinkRequest(String code, long telegramId) {
        CompletableFuture<String> future = new CompletableFuture<>();

        new Thread(() -> {
            NioEventLoopGroup group = new NioEventLoopGroup();
            try {
                Bootstrap bootstrap = new Bootstrap()
                        .group(group)
                        .channel(NioSocketChannel.class)
                        .handler(new ChannelInitializer<Channel>() {
                            @Override
                            protected void initChannel(Channel ch) {
                                ch.pipeline().addLast(new LineBasedFrameDecoder(1024));
                                ch.pipeline().addLast(new StringDecoder(StandardCharsets.UTF_8));
                                ch.pipeline().addLast(new StringEncoder(StandardCharsets.UTF_8));
                                ch.pipeline().addLast(new SimpleChannelInboundHandler<String>() {
                                    @Override
                                    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
                                        future.complete(msg);
                                        ctx.close();
                                    }
                                });
                            }
                        });

                ChannelFuture f = bootstrap.connect("127.0.0.1", 25422).sync();
                f.channel().writeAndFlush("auth_unlink " + code + " " + telegramId + "\n");
                f.channel().closeFuture().sync();

            } catch (Exception e) {
                future.completeExceptionally(e);
            } finally {
                group.shutdownGracefully();
            }
        }).start();

        return future;
    }

    public static CompletableFuture<String> sendAuthCommand(String command) {
        CompletableFuture<String> future = new CompletableFuture<>();

        new Thread(() -> {
            NioEventLoopGroup group = new NioEventLoopGroup();
            try {
                Bootstrap bootstrap = new Bootstrap()
                        .group(group)
                        .channel(NioSocketChannel.class)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                        .handler(new ChannelInitializer<Channel>() {
                            @Override
                            protected void initChannel(Channel ch) {
                                ch.pipeline().addLast(new LineBasedFrameDecoder(1024));
                                ch.pipeline().addLast(new StringDecoder(StandardCharsets.UTF_8));
                                ch.pipeline().addLast(new StringEncoder(StandardCharsets.UTF_8));
                                ch.pipeline().addLast(new SimpleChannelInboundHandler<String>() {
                                    @Override
                                    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
                                        future.complete(msg.trim());
                                        ctx.close();
                                    }

                                    @Override
                                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                        future.completeExceptionally(cause);
                                        ctx.close();
                                    }
                                });
                            }
                        });

                ChannelFuture f = bootstrap.connect("localhost", 25422).sync();
                f.channel().writeAndFlush(command + "\n");
                f.channel().closeFuture().sync();

            } catch (Exception e) {
                future.completeExceptionally(e);
            } finally {
                group.shutdownGracefully();
            }
        }).start();

        return future;
    }


    private void handleSuccess(int index) {
        notificationSent[index] = false;

        if (!serverStatus[index]) {
            serverStatus[index] = true;
            retryCounts[index] = 0;
            sendTelegramMessage("✅ Связь с `" + serverNames[index] + "` успешно восстановлена!\n\n#" + serverNames[index].toLowerCase() + " #connection", threadIds[index]);
        }
        System.out.println("✅ Сервер " + serverNames[index] + " онлайн");
    }

    private void handleFailure(int index, String reason) {
        System.out.println("❌ Сервер " + serverNames[index] + " не отвечает: " + reason);
        if (System.currentTimeMillis() - lastFailedTime[index] < retryDelayMillis) return;

        retryCounts[index]++;
        if (retryCounts[index] <= maxRetries) {
            System.out.println("🔄 Попытка №" + retryCounts[index] + " для " + serverNames[index]);
        } else {
            if (serverStatus[index]) {
                serverStatus[index] = false;
                sendTelegramMessage("❌ Не удалось подключиться к `" + serverNames[index] + "` после " + maxRetries + " попыток переподключения.", threadIds[index]);
            }
            lastFailedTime[index] = System.currentTimeMillis();

            if (!notificationSent[index]) {
                sendTelegramMessage("🚧 Не могу установить связь с `" + serverNames[index] + "`. Надо что-то делать!\n\n#" + serverNames[index].toLowerCase() + " #connection", threadIds[index]);
                notificationSent[index] = true;
            }
        }
    }

    private void sendTelegramMessage(String text, int threadId) {
        messageService.sendMessage(-1002521750905L, threadId, text);
    }

    private String escapeHtml(String text) {
        return haunt.trust.utils.MessageService.escapeHtml(text);
    }

    private String escapeMarkdownV2(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\")
                .replace("_", "\\_")
                .replace("*", "\\*")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("~", "\\~")
                .replace("`", "\\`")
                .replace(">", "\\>")
                .replace("#", "\\#")
                .replace("+", "\\+")
                .replace("-", "\\-")
                .replace("=", "\\=")
                .replace("|", "\\|")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace(".", "\\.")
                .replace("!", "\\!");
    }

    private String escapeMarkdownV2Code(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\")
                .replace("`", "\\`");
    }


    public void shutdown() {
        group.shutdownGracefully();
        scheduler.shutdown();
    }
}
