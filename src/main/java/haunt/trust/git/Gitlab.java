package haunt.trust.git;

import haunt.trust.config.EnvConfig;
import haunt.trust.utils.MessageService;
import com.google.gson.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class Gitlab {
    private final MessageService messageService;
    private final Gson gson = new Gson();
    private GitlabWebhookServer webhookServer;

    /* === INIT CONFIG GITLAB === */
    private final String gitlabUrl = EnvConfig.getGitLabUrl();
    private final String projectId = EnvConfig.getGitLabProjectId();
    private final String accessToken = EnvConfig.getGitLabAccessToken();
    private String projectName = "GitLab #" + projectId;

    /* === ID CHAT and THREAD ID IF PRESENT === */
    private final Long mainChatId = -1001904947967L;
    private final int commitThreadId = 77177;

    private final Map<String, String> branchLastCommits = new HashMap<>(); // branch_name -> last_commit_id
    private final Set<String> allTrackedBranches = new HashSet<>();
    private final Map<Integer, String> lastMergeRequestStates = new HashMap<>(); // MR_ID -> state
    private final Set<Integer> allTrackedMRs = new HashSet<>();

    private final String stateFile = "gitlab_state.json";

    public Gitlab(MessageService messageService) {
        this.messageService = messageService;
        loadProjectName();
        if (EnvConfig.isGitLabPollingEnabled()) {
            loadState();
            startPolling();
        }
        startWebhook();
    }

    private void startWebhook() {
        String secret = EnvConfig.getGitLabWebhookSecret();
        String signingToken = EnvConfig.getGitLabWebhookSigningToken();
        if (secret.isBlank() && signingToken.isBlank()) {
            System.out.println("GitLab webhook disabled: authentication token is empty");
            return;
        }

        try {
            webhookServer = new GitlabWebhookServer(
                    EnvConfig.getGitLabWebhookPort(),
                    EnvConfig.getGitLabWebhookPath(),
                    secret,
                    signingToken,
                    new GitlabWebhookHandler(messageService, mainChatId, commitThreadId, projectId)
            );
            webhookServer.start();
            System.out.println("GitLab webhook started on :" + EnvConfig.getGitLabWebhookPort()
                    + EnvConfig.getGitLabWebhookPath());
        } catch (Exception exception) {
            System.err.println("GitLab webhook failed to start: " + exception.getMessage());
        }
    }

    private void loadState() {
        try {
            Path path = Paths.get(stateFile);
            if (Files.exists(path)) {
                String content = new String(Files.readAllBytes(path));
                JsonObject state = gson.fromJson(content, JsonObject.class);

                if (state.has("trackedBranches")) {
                    JsonArray branches = state.getAsJsonArray("trackedBranches");
                    for (JsonElement branch : branches) {
                        allTrackedBranches.add(branch.getAsString());
                    }
                }

                if (state.has("branchCommits")) {
                    JsonObject commits = state.getAsJsonObject("branchCommits");
                    for (Map.Entry<String, JsonElement> entry : commits.entrySet()) {
                        branchLastCommits.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }

                if (state.has("trackedMRs")) {
                    JsonArray mrs = state.getAsJsonArray("trackedMRs");
                    for (JsonElement mr : mrs) {
                        allTrackedMRs.add(mr.getAsInt());
                    }
                }

                if (state.has("mrStates")) {
                    JsonObject mrStates = state.getAsJsonObject("mrStates");
                    for (Map.Entry<String, JsonElement> entry : mrStates.entrySet()) {
                        lastMergeRequestStates.put(Integer.parseInt(entry.getKey()), entry.getValue().getAsString());
                    }
                }

                System.out.println("✅ Состояние загружено: " + allTrackedBranches.size() + " веток, " +
                        branchLastCommits.size() + " коммитов, " + allTrackedMRs.size() + " MRs");
            }
        } catch (Exception e) {
            System.err.println("❌ Ошибка загрузки состояния: " + e.getMessage());
        }
    }

    private synchronized void saveState() {
        try {
            JsonObject state = new JsonObject();

            JsonArray branches = new JsonArray();
            for (String branch : allTrackedBranches) {
                branches.add(branch);
            }
            state.add("trackedBranches", branches);

            JsonObject commits = new JsonObject();
            for (Map.Entry<String, String> entry : branchLastCommits.entrySet()) {
                commits.addProperty(entry.getKey(), entry.getValue());
            }
            state.add("branchCommits", commits);

            JsonArray mrs = new JsonArray();
            for (Integer mrId : allTrackedMRs) {
                mrs.add(mrId);
            }
            state.add("trackedMRs", mrs);

            JsonObject mrStates = new JsonObject();
            for (Map.Entry<Integer, String> entry : lastMergeRequestStates.entrySet()) {
                mrStates.addProperty(entry.getKey().toString(), entry.getValue());
            }
            state.add("mrStates", mrStates);

            state.addProperty("lastSave", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            Files.write(Paths.get(stateFile), gson.toJson(state).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("❌ Ошибка сохранения состояния: " + e.getMessage());
        }
    }


    private void startPolling() {
        Thread pollingThread = new Thread(() -> {
            while (true) {
                try {
                    checkNewBranchesAndCommits();
                    checkMergeRequests();
                    saveState();
                    Thread.sleep(EnvConfig.getPollingInterval());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    System.err.println("❌ GitLab polling cycle failed: " + e.getMessage());
                }
            }
        });
        pollingThread.setDaemon(true);
        pollingThread.start();
        System.out.println("✅ GitLab polling started (branches + MRs)...");
    }

    private void checkMergeRequests() {
        try {
            String apiUrl = gitlabUrl + "/projects/" + projectId + "/merge_requests?state=opened&per_page=20";

            JsonArray mergeRequests = fetchGitLabAPI(apiUrl);
            if (mergeRequests != null) {
                for (JsonElement element : mergeRequests) {
                    JsonObject mr = element.getAsJsonObject();
                    int mrId = mr.get("iid").getAsInt();
                    String mrState = mr.get("state").getAsString();

                    if (!allTrackedMRs.contains(mrId)) {
                        sendNewMRNotification(mr);
                        rememberMergeRequestState(mrId, mrState);
                    } else {
                        String lastState = lastMergeRequestStates.get(mrId);
                        if ("merged".equals(lastState)) {
                            continue;
                        }
                        if (!mrState.equals(lastState)) {
                            sendMRStateChangeNotification(mr, lastState, mrState);
                            rememberMergeRequestState(mrId, mrState);
                        }
                    }
                }
            }

            checkTerminalMergeRequests("closed");
            checkTerminalMergeRequests("merged");

        } catch (Exception e) {
            System.err.println("❌ Error checking merge requests: " + e.getMessage());
        }
    }

    private void checkTerminalMergeRequests(String state) {
        try {
            String apiUrl = gitlabUrl + "/projects/" + projectId + "/merge_requests?state=" + state + "&per_page=10";

            JsonArray mergeRequests = fetchGitLabAPI(apiUrl);
            if (mergeRequests == null) return;

            for (JsonElement element : mergeRequests) {
                JsonObject mr = element.getAsJsonObject();
                int mrId = mr.get("iid").getAsInt();
                String currentState = mr.get("state").getAsString();

                if (!allTrackedMRs.contains(mrId)) {
                    rememberMergeRequestState(mrId, currentState);
                    continue;
                }

                String lastState = lastMergeRequestStates.get(mrId);
                if ("merged".equals(lastState)) {
                    continue;
                }

                if (!currentState.equals(lastState)) {
                    sendMRStateChangeNotification(mr, lastState, currentState);
                    rememberMergeRequestState(mrId, currentState);
                }
            }

        } catch (Exception e) {
            System.err.println("❌ Error checking " + state + " merge requests: " + e.getMessage());
        }
    }

    private void rememberMergeRequestState(int mrId, String state) {
        allTrackedMRs.add(mrId);
        lastMergeRequestStates.put(mrId, state);
        saveState();
    }

    private void sendNewMRNotification(JsonObject mr) {
        try {
            int mrId = mr.get("iid").getAsInt();
            String mrTitle = mr.get("title").getAsString();
            String author = mr.get("author").getAsJsonObject().get("name").getAsString();
            String sourceBranch = mr.get("source_branch").getAsString();
            String targetBranch = mr.get("target_branch").getAsString();
            String mrUrl = mr.get("web_url").getAsString();
            String description = mr.has("description") && !mr.get("description").isJsonNull() ?
                    mr.get("description").getAsString() : "Описание отсутствует";

            String message = GitNotificationFormatter.changeRequestOpened(
                    "GitLab",
                    EnvConfig.getTelegramMergeEmojiId(),
                    "MR",
                    mrId,
                    projectName,
                    mrTitle,
                    author,
                    sourceBranch,
                    targetBranch,
                    description,
                    mrUrl
            );

            messageService.sendMessageHtml(mainChatId, commitThreadId, message);

        } catch (Exception e) {
            System.err.println("❌ Error sending new MR notification: " + e.getMessage());
        }
    }


    private void sendMRStateChangeNotification(JsonObject mr, String oldState, String newState) {
        try {
            int mrId = mr.get("iid").getAsInt();
            String mrTitle = mr.get("title").getAsString();
            String mrUrl = mr.get("web_url").getAsString();
            String sourceBranch = mr.get("source_branch").getAsString();
            String targetBranch = mr.get("target_branch").getAsString();

            String stateEmoji;
            String stateText;

            switch (newState) {
                case "merged":
                    stateEmoji = "✅";
                    stateText = "слит";
                    break;
                case "closed":
                    stateEmoji = "❌";
                    stateText = "закрыт";
                    break;
                case "opened":
                case "reopened":
                    stateEmoji = "🔄";
                    stateText = "переоткрыт";
                    break;
                default:
                    return;
            }

            String message = GitNotificationFormatter.changeRequestState(
                    "GitLab",
                    EnvConfig.getTelegramMergeEmojiId(),
                    "MR",
                    mrId,
                    projectName,
                    mrTitle,
                    sourceBranch,
                    targetBranch,
                    stateText,
                    stateEmoji,
                    mrUrl
            );

            messageService.sendMessageHtml(mainChatId, commitThreadId, message);

        } catch (Exception e) {
            System.err.println("❌ Error sending MR state change notification: " + e.getMessage());
        }
    }

    private void checkNewBranchesAndCommits() {
        try {
            List<String> currentBranches = getAllBranches();
            detectNewBranches(currentBranches);

            for (String branch : allTrackedBranches) {
                checkCommitsInBranch(branch);
            }

        } catch (Exception e) {
            System.err.println("❌ Error checking branches and commits: " + e.getMessage());
        }
    }

    private void detectNewBranches(List<String> currentBranches) {
        Set<String> newBranches = new HashSet<>(currentBranches);
        newBranches.removeAll(allTrackedBranches);

        if (!newBranches.isEmpty()) {
            System.out.println("🎉 New branches detected: " + newBranches);
            for (String newBranch : newBranches) {
                if (!allTrackedBranches.isEmpty()) {
                    sendNewBranchNotification(newBranch);
                }
            }
            allTrackedBranches.addAll(newBranches);
        }
    }

    private List<String> getAllBranches() {
        List<String> branches = new ArrayList<>();
        try {
            String apiUrl = gitlabUrl + "/projects/" + projectId + "/repository/branches?per_page=100";

            JsonArray branchesJson = fetchGitLabAPI(apiUrl);
            if (branchesJson != null) {
                for (JsonElement element : branchesJson) {
                    JsonObject branch = element.getAsJsonObject();
                    String branchName = branch.get("name").getAsString();
                    branches.add(branchName);
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Error fetching branches: " + e.getMessage());
        }
        return branches;
    }

    private void checkCommitsInBranch(String branchName) {
        try {
            String apiUrl = gitlabUrl + "/projects/" + projectId + "/repository/commits?ref_name=" + branchName + "&per_page=50";

            JsonArray commits = fetchGitLabAPI(apiUrl);
            if (commits == null || commits.size() == 0) {
                return;
            }

            JsonObject latestCommit = commits.get(0).getAsJsonObject();
            String commitId = latestCommit.get("id").getAsString();
            String lastKnownCommit = branchLastCommits.get(branchName);
            if (lastKnownCommit == null) {
                branchLastCommits.put(branchName, commitId);
                System.out.println("📝 Branch '" + branchName + "' initialized with commit: " + commitId.substring(0, 8));
            } else if (!lastKnownCommit.equals(commitId)) {
                System.out.println("🎉 New commit in branch '" + branchName + "': " + commitId.substring(0, 8));
                List<JsonObject> newCommits = collectNewCommits(commits, lastKnownCommit);
                for (JsonObject commit : newCommits) {
                    sendCommitNotification(commit, branchName);
                }
                branchLastCommits.put(branchName, commitId);
            }

        } catch (Exception e) {
            System.err.println("❌ Error checking commits in branch '" + branchName + "': " + e.getMessage());
        }
    }

    private List<JsonObject> collectNewCommits(JsonArray commits, String lastKnownCommit) {
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement element : commits) {
            JsonObject commit = element.getAsJsonObject();
            String id = commit.get("id").getAsString();
            if (id.equals(lastKnownCommit)) {
                break;
            }
            result.add(commit);
        }
        Collections.reverse(result);
        return result;
    }

    private void sendNewBranchNotification(String branchName) {
        try {
            String message = GitNotificationFormatter.branch(
                    "GitLab",
                    EnvConfig.getTelegramGitLabEmojiId(),
                    projectName,
                    branchName,
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
            );

            messageService.sendMessageHtml(mainChatId, commitThreadId, message);

        } catch (Exception e) {
            System.err.println("❌ Error sending new branch notification: " + e.getMessage());
        }
    }

    private void sendCommitNotification(JsonObject commit, String branchName) {
        try {
            String commitId = commit.get("id").getAsString().substring(0, 8);
            String commitMessage = commit.get("message").getAsString();
            String authorName = commit.get("author_name").getAsString();
            String commitDate = commit.get("committed_date").getAsString();
            String commitUrl = commit.get("web_url").getAsString();

            String message = GitNotificationFormatter.commit(
                    "GitLab",
                    EnvConfig.getTelegramGitLabEmojiId(),
                    projectName,
                    branchName,
                    authorName,
                    commitId,
                    formatDate(commitDate),
                    commitMessage,
                    commitUrl
            );

            messageService.sendMessageHtml(mainChatId, commitThreadId, message);

        } catch (Exception e) {
            System.err.println("❌ Error sending commit notification: " + e.getMessage());
        }
    }

    private JsonArray fetchGitLabAPI(String apiUrl) {
        try {
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("PRIVATE-TOKEN", accessToken);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String inputLine;
                StringBuilder content = new StringBuilder();

                while ((inputLine = in.readLine()) != null) {
                    content.append(inputLine);
                }
                in.close();
                conn.disconnect();

                return gson.fromJson(content.toString(), JsonArray.class);
            } else {
                System.err.println("❌ GitLab API error: " + responseCode + " for URL: " + apiUrl);
            }
        } catch (Exception e) {
            System.err.println("❌ GitLab API connection error: " + e.getMessage());
        }
        return null;
    }

    private void loadProjectName() {
        try {
            JsonObject project = fetchGitLabObject(gitlabUrl + "/projects/" + projectId);
            if (project == null) {
                return;
            }

            if (project.has("path_with_namespace")) {
                projectName = project.get("path_with_namespace").getAsString();
            } else if (project.has("name")) {
                projectName = project.get("name").getAsString();
            }
        } catch (Exception e) {
            System.err.println("⚠️ Unable to load GitLab project name: " + e.getMessage());
        }
    }

    private JsonObject fetchGitLabObject(String apiUrl) {
        try {
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("PRIVATE-TOKEN", accessToken);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            if (conn.getResponseCode() != 200) {
                conn.disconnect();
                return null;
            }

            try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                StringBuilder content = new StringBuilder();
                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    content.append(inputLine);
                }
                return gson.fromJson(content.toString(), JsonObject.class);
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private String formatDate(String isoDate) {
        try {
            Instant instant = Instant.parse(isoDate);
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("Europe/Moscow"));
            return formatter.format(instant);
        } catch (Exception e) {
            return isoDate;
        }
    }

    private String escapeHtml(String text) {
        return haunt.trust.utils.MessageService.escapeHtml(text);
    }

    public void sendStatusReport() {
        try {
            String message = String.format(
                    "\uD83E\uDD8A<b>GitLab</b>\n\n" +
                            "🌿 <b>Отслеживаемых веток:</b> %d\n" +
                            "📝 <b>Всего коммитов:</b> %d\n" +
                            "🕒 <b>Последняя проверка:</b> %s\n\n" +
                            "Ветки: %s",
                    allTrackedBranches.size(),
                    branchLastCommits.size(),
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")),
                    String.join(", ", allTrackedBranches)
            );

            messageService.sendMessageHtml(mainChatId, commitThreadId, message);

        } catch (Exception e) {
            System.err.println("❌ Error sending status report: " + e.getMessage());
        }
    }
}
