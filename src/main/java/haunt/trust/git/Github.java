package haunt.trust.git;

import haunt.trust.config.EnvConfig;
import haunt.trust.utils.MessageService;
import com.google.gson.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class Github {
    private final MessageService messageService;
    private final Gson gson = new Gson();

    /* === INIT CONFIG GITHUB === */
    private final String githubUrl = EnvConfig.getGitHubUrl();
    private final String accessToken = EnvConfig.getGitHubAccessToken(); // GitHub Personal Access Token

    /* === ID CHAT and THREAD ID IF PRESENT === */
    private final Long mainChatId = -1001904947967L;
    private final int commitThreadId = 77177;

    private final List<Repository> repositories = Arrays.asList(
            new Repository("TailsXPrower", "CivMod")
    );

    private final Map<String, Map<String, String>> branchLastCommits = new HashMap<>(); // repo_full_name -> (branch_name -> last_commit_sha)
    private final Map<String, Set<String>> allTrackedBranches = new HashMap<>();

    private final Map<String, Map<Integer, String>> pullRequestStates = new HashMap<>(); // repo -> (pr_number -> state)
    private final Map<String, Set<Integer>> allTrackedPRs = new HashMap<>();

    private final String stateFile = "github_state.json";

    private static class Repository {
        final String owner;
        final String repo;

        Repository(String owner, String repo) {
            this.owner = owner;
            this.repo = repo;
        }


        String getFullName() {
            return owner + "/" + repo;
        }

        @Override
        public String toString() {
            return repo;
        }
    }

    public Github(MessageService messageService) {
        this.messageService = messageService;

        for (Repository repo : repositories) {
            String repoKey = repo.getFullName();
            branchLastCommits.put(repoKey, new HashMap<>());
            allTrackedBranches.put(repoKey, new HashSet<>());
            pullRequestStates.put(repoKey, new HashMap<>());
            allTrackedPRs.put(repoKey, new HashSet<>());
        }

        loadState();
        startPolling();
    }

    private void loadState() {
        try {
            Path path = Paths.get(stateFile);
            if (Files.exists(path)) {
                String content = new String(Files.readAllBytes(path));
                JsonObject state = gson.fromJson(content, JsonObject.class);

                if (state.has("repositories")) {
                    JsonObject reposState = state.getAsJsonObject("repositories");
                    for (Repository repo : repositories) {
                        String repoKey = repo.getFullName();
                        if (reposState.has(repoKey)) {
                            JsonObject repoState = reposState.getAsJsonObject(repoKey);

                            if (repoState.has("trackedBranches")) {
                                JsonArray branches = repoState.getAsJsonArray("trackedBranches");
                                for (JsonElement branch : branches) {
                                    allTrackedBranches.get(repoKey).add(branch.getAsString());
                                }
                            }

                            if (repoState.has("branchCommits")) {
                                JsonObject commits = repoState.getAsJsonObject("branchCommits");
                                for (Map.Entry<String, JsonElement> entry : commits.entrySet()) {
                                    branchLastCommits.get(repoKey).put(entry.getKey(), entry.getValue().getAsString());
                                }
                            }

                            // ЗАГРУЗКА PULL REQUESTS
                            if (repoState.has("trackedPRs")) {
                                JsonArray prs = repoState.getAsJsonArray("trackedPRs");
                                for (JsonElement pr : prs) {
                                    allTrackedPRs.get(repoKey).add(pr.getAsInt());
                                }
                            }

                            if (repoState.has("prStates")) {
                                JsonObject prStates = repoState.getAsJsonObject("prStates");
                                for (Map.Entry<String, JsonElement> entry : prStates.entrySet()) {
                                    pullRequestStates.get(repoKey).put(
                                            Integer.parseInt(entry.getKey()),
                                            entry.getValue().getAsString()
                                    );
                                }
                            }
                        }
                    }
                }

                System.out.println("✅ Состояние GitHub загружено для " + repositories.size() + " репозиториев");
            }
        } catch (Exception e) {
            System.err.println("❌ Ошибка загрузки состояния GitHub: " + e.getMessage());
        }
    }


    private void saveState() {
        try {
            JsonObject state = new JsonObject();
            JsonObject reposState = new JsonObject();

            for (Repository repo : repositories) {
                String repoKey = repo.getFullName();
                JsonObject repoState = new JsonObject();

                JsonArray branches = new JsonArray();
                for (String branch : allTrackedBranches.get(repoKey)) {
                    branches.add(branch);
                }
                repoState.add("trackedBranches", branches);

                JsonObject commits = new JsonObject();
                for (Map.Entry<String, String> entry : branchLastCommits.get(repoKey).entrySet()) {
                    commits.addProperty(entry.getKey(), entry.getValue());
                }
                repoState.add("branchCommits", commits);

                JsonArray prs = new JsonArray();
                for (Integer prNumber : allTrackedPRs.get(repoKey)) {
                    prs.add(prNumber);
                }
                repoState.add("trackedPRs", prs);

                JsonObject prStates = new JsonObject();
                for (Map.Entry<Integer, String> entry : pullRequestStates.get(repoKey).entrySet()) {
                    prStates.addProperty(entry.getKey().toString(), entry.getValue());
                }
                repoState.add("prStates", prStates);

                reposState.add(repoKey, repoState);
            }

            state.add("repositories", reposState);
            state.addProperty("lastSave", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            Files.write(Paths.get(stateFile), gson.toJson(state).getBytes());
        } catch (Exception e) {
            System.err.println("❌ Ошибка сохранения состояния GitHub: " + e.getMessage());
        }
    }

    private void startPolling() {
        Thread pollingThread = new Thread(() -> {
            while (true) {
                try {
                    checkAllRepositories();
                    Thread.sleep(180000); // Проверка каждую минуту
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
        pollingThread.setDaemon(true);
        pollingThread.start();
        System.out.println("✅ GitHub polling started for " + repositories.size() + " repositories...");
    }

    private void checkAllRepositories() {
        for (Repository repo : repositories) {
            try {
                checkRepository(repo);
            } catch (Exception e) {
                System.err.println("❌ Error checking repository " + repo.getFullName() + ": " + e.getMessage());
            }
        }
        saveState();
    }

    private void checkRepository(Repository repository) {
        String repoKey = repository.getFullName();

        List<String> currentBranches = getBranchesForRepo(repository);
        if (currentBranches == null) {
            return;
        }
        synchronizeBranches(repository, currentBranches);

        for (String branch : currentBranches) {
            checkCommitsInBranch(repository, branch);
        }
        checkPullRequests(repository);
    }

    private void synchronizeBranches(Repository repository, List<String> currentBranches) {
        String repoKey = repository.getFullName();
        Set<String> trackedBranches = allTrackedBranches.get(repoKey);
        Set<String> currentBranchSet = new HashSet<>(currentBranches);

        Set<String> newBranches = new HashSet<>(currentBranchSet);
        newBranches.removeAll(trackedBranches);

        if (!newBranches.isEmpty()) {
            System.out.println("🎉 New branches detected in " + repoKey + ": " + newBranches);
            for (String newBranch : newBranches) {
                if (!trackedBranches.isEmpty()) {
                    sendNewBranchNotification(repository, newBranch);
                }
            }
        }

        Set<String> deletedBranches = new HashSet<>(trackedBranches);
        deletedBranches.removeAll(currentBranchSet);
        if (!deletedBranches.isEmpty()) {
            System.out.println("Removed deleted GitHub branches from tracking in " + repoKey + ": " + deletedBranches);
            branchLastCommits.get(repoKey).keySet().removeAll(deletedBranches);
        }

        trackedBranches.clear();
        trackedBranches.addAll(currentBranchSet);
    }

    private List<String> getBranchesForRepo(Repository repository) {
        List<String> branches = new ArrayList<>();
        try {
            String apiUrl = githubUrl + "/repos/" + repository.getFullName() + "/branches?per_page=100";

            JsonArray branchesJson = fetchGithubAPI(apiUrl);
            if (branchesJson == null) {
                return null;
            }
            for (JsonElement element : branchesJson) {
                JsonObject branch = element.getAsJsonObject();
                String branchName = branch.get("name").getAsString();
                branches.add(branchName);
            }
        } catch (Exception e) {
            System.err.println("❌ Error fetching branches for " + repository.getFullName() + ": " + e.getMessage());
            return null;
        }
        return branches;
    }

    private void checkCommitsInBranch(Repository repository, String branchName) {
        try {
            String encodedBranch = URLEncoder.encode(branchName, StandardCharsets.UTF_8);
            String apiUrl = githubUrl + "/repos/" + repository.getFullName() + "/commits?sha=" + encodedBranch + "&per_page=50";

            JsonArray commits = fetchGithubAPI(apiUrl);
            if (commits == null || commits.size() == 0) {
                return;
            }

            JsonObject latestCommit = commits.get(0).getAsJsonObject();
            String commitSha = latestCommit.get("sha").getAsString();
            String repoKey = repository.getFullName();
            Map<String, String> repoCommits = branchLastCommits.get(repoKey);

            String lastKnownCommit = repoCommits.get(branchName);
            if (lastKnownCommit == null) {
                repoCommits.put(branchName, commitSha);
                System.out.println("📝 " + repoKey + " branch '" + branchName + "' initialized");
            } else if (!lastKnownCommit.equals(commitSha)) {
                System.out.println("🎉 New commit in " + repoKey + " branch '" + branchName + "': " + commitSha.substring(0, 8));
                List<JsonObject> newCommits = collectNewCommits(commits, lastKnownCommit);
                for (JsonObject commit : newCommits) {
                    sendCommitNotification(repository, commit, branchName);
                }
                repoCommits.put(branchName, commitSha);
            }

        } catch (Exception e) {
            System.err.println("❌ Error checking commits in " + repository.getFullName() + " branch '" + branchName + "': " + e.getMessage());
        }
    }

    private List<JsonObject> collectNewCommits(JsonArray commits, String lastKnownCommit) {
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement element : commits) {
            JsonObject commit = element.getAsJsonObject();
            String sha = commit.get("sha").getAsString();
            if (sha.equals(lastKnownCommit)) {
                break;
            }
            result.add(commit);
        }
        Collections.reverse(result);
        return result;
    }

    private void sendNewBranchNotification(Repository repository, String branchName) {
        try {
            String message = GitNotificationFormatter.branch(
                    "GitHub",
                    EnvConfig.getTelegramGitHubEmojiId(),
                    repository.toString(),
                    branchName,
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
            );

            messageService.sendMessageHtml(mainChatId, commitThreadId, message);

        } catch (Exception e) {
            System.err.println("❌ Error sending GitHub new branch notification: " + e.getMessage());
        }
    }

    private void sendCommitNotification(Repository repository, JsonObject commit, String branchName) {
        try {
            String commitSha = commit.get("sha").getAsString();
            JsonObject commitDetails = commit.getAsJsonObject("commit");
            String commitMessage = commitDetails.get("message").getAsString();
            String authorName = commitDetails.get("author").getAsJsonObject().get("name").getAsString();
            String commitDate = commitDetails.get("author").getAsJsonObject().get("date").getAsString();
            String commitUrl = commit.get("html_url").getAsString();

            String message = GitNotificationFormatter.commit(
                    "GitHub",
                    EnvConfig.getTelegramGitHubEmojiId(),
                    repository.toString(),
                    branchName,
                    authorName,
                    commitSha.substring(0, 8),
                    formatDate(commitDate),
                    commitMessage,
                    commitUrl
            );

            messageService.sendMessageHtml(mainChatId, commitThreadId, message);

        } catch (Exception e) {
            System.err.println("❌ Error sending GitHub commit notification: " + e.getMessage());
        }
    }

    private JsonArray fetchGithubAPI(String apiUrl) {
        try {
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setRequestProperty("User-Agent", "Telegram-Bot");
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
                System.err.println("❌ GitHub API error: " + responseCode + " for URL: " + apiUrl);
            }
        } catch (Exception e) {
            System.err.println("❌ GitHub API connection error: " + e.getMessage());
        }
        return null;
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
        return MessageService.escapeHtml(text);
    }

    public void sendStatusReport() {
        try {
            StringBuilder message = new StringBuilder();
            message.append("🐙 <b>GitHub Status Report</b>\n\n");

            for (Repository repo : repositories) {
                String repoKey = repo.getFullName();
                int branchCount = allTrackedBranches.get(repoKey).size();
                int commitCount = branchLastCommits.get(repoKey).size();
                message.append(String.format(
                        "📦 <b>%s</b> (%s)\n" +
                                "🌿 Веток: %d | 📝 Коммитов: %d\n\n",
                        repo, repoKey, branchCount, commitCount
                ));
            }

            message.append("🕒 <b>Последняя проверка:</b> ")
                    .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")));

            messageService.sendMessageHtml(mainChatId, commitThreadId, message.toString());

        } catch (Exception e) {
            System.err.println("❌ Error sending GitHub status report: " + e.getMessage());
        }
    }


    private void sendPRStateChangeNotification(Repository repository, JsonObject pr, String oldState, String newState) {
        try {
            int prNumber = pr.get("number").getAsInt();
            String prTitle = pr.get("title").getAsString();
            String prUrl = pr.get("html_url").getAsString();
            String sourceBranch = pr.get("head").getAsJsonObject().get("ref").getAsString();
            String targetBranch = pr.get("base").getAsJsonObject().get("ref").getAsString();

            String stateEmoji;
            String stateText;

            if (newState.equals("closed_merged")) {
                stateEmoji = "✅";
                stateText = "слит";
            } else if (newState.equals("closed")) {
                stateEmoji = "❌";
                stateText = "закрыт";
            } else if (newState.equals("open") && oldState != null && oldState.startsWith("closed")) {
                stateEmoji = "🔄";
                stateText = "переоткрыт";
            } else {
                return;
            }

            String message = GitNotificationFormatter.changeRequestState(
                    "GitHub",
                    EnvConfig.getTelegramMergeEmojiId(),
                    "PR",
                    prNumber,
                    repository.toString(),
                    prTitle,
                    sourceBranch,
                    targetBranch,
                    stateText,
                    stateEmoji,
                    prUrl
            );

            messageService.sendMessageHtml(mainChatId, commitThreadId, message);

        } catch (Exception e) {
            System.err.println("❌ Error sending PR state change notification: " + e.getMessage());
        }
    }

    private void sendNewPRNotification(Repository repository, JsonObject pr) {
        try {
            int prNumber = pr.get("number").getAsInt();
            String prTitle = pr.get("title").getAsString();
            String author = pr.get("user").getAsJsonObject().get("login").getAsString();
            String sourceBranch = pr.get("head").getAsJsonObject().get("ref").getAsString();
            String targetBranch = pr.get("base").getAsJsonObject().get("ref").getAsString();
            String prUrl = pr.get("html_url").getAsString();
            String body = pr.has("body") && !pr.get("body").isJsonNull() ?
                    pr.get("body").getAsString() : "Описание отсутствует";

            String message = GitNotificationFormatter.changeRequestOpened(
                    "GitHub",
                    EnvConfig.getTelegramMergeEmojiId(),
                    "PR",
                    prNumber,
                    repository.toString(),
                    prTitle,
                    author,
                    sourceBranch,
                    targetBranch,
                    body,
                    prUrl
            );

            messageService.sendMessageHtml(mainChatId, commitThreadId, message);

        } catch (Exception e) {
            System.err.println("❌ Error sending new PR notification: " + e.getMessage());
        }
    }

    private void processPullRequest(Repository repository, JsonObject pr, String currentState) {
        try {
            String repoKey = repository.getFullName();
            int prNumber = pr.get("number").getAsInt();
            String prTitle = pr.get("title").getAsString();
            String prState = pr.get("state").getAsString();
            boolean isMerged = pr.has("merged_at") && !pr.get("merged_at").isJsonNull();

            Set<Integer> trackedPRs = allTrackedPRs.get(repoKey);
            Map<Integer, String> prStates = pullRequestStates.get(repoKey);

            if (!trackedPRs.contains(prNumber)) {
                sendNewPRNotification(repository, pr);
                trackedPRs.add(prNumber);
                prStates.put(prNumber, prState + (isMerged ? "_merged" : ""));
            } else {
                String lastState = prStates.get(prNumber);
                String newState = prState + (isMerged ? "_merged" : "");

                if (!newState.equals(lastState)) {
                    sendPRStateChangeNotification(repository, pr, lastState, newState);
                    prStates.put(prNumber, newState);
                }
            }

        } catch (Exception e) {
            System.err.println("❌ Error processing PR for " + repository.getFullName() + ": " + e.getMessage());
        }
    }

    private void checkPullRequests(Repository repository) {
        try {
            String repoKey = repository.getFullName();

            String openPrsUrl = githubUrl + "/repos/" + repoKey + "/pulls?state=open&per_page=20";
            JsonArray openPRs = fetchGithubAPI(openPrsUrl);

            if (openPRs != null) {
                for (JsonElement element : openPRs) {
                    JsonObject pr = element.getAsJsonObject();
                    processPullRequest(repository, pr, "open");
                }
            }

            String closedPrsUrl = githubUrl + "/repos/" + repoKey + "/pulls?state=closed&per_page=10";
            JsonArray closedPRs = fetchGithubAPI(closedPrsUrl);

            if (closedPRs != null) {
                for (JsonElement element : closedPRs) {
                    JsonObject pr = element.getAsJsonObject();
                    processPullRequest(repository, pr, "closed");
                }
            }

        } catch (Exception e) {
            System.err.println("❌ Error checking pull requests for " + repository.getFullName() + ": " + e.getMessage());
        }
    }

}
