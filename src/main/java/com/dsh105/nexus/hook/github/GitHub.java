/*
 * This file is part of Nexus.
 *
 * Nexus is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Nexus is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Nexus.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.dsh105.nexus.hook.github;

import com.dsh105.nexus.Nexus;
import com.dsh105.nexus.exception.github.*;
import com.dsh105.nexus.hook.github.gist.Gist;
import com.dsh105.nexus.hook.github.gist.GistFile;
import com.dsh105.nexus.util.JsonUtil;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.async.Callback;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.util.*;

public class GitHub {

    public static String API_URL = "https://api.github.com";
    public static String REPO_API_URL = API_URL + "/repos/{name}";
    public static String USER_API_URL = API_URL + "/users/{name}";
    public static String RATE_LIMIT_API_URL = API_URL + "/rate_limit";
    public static String GISTS_API_URL = API_URL + "/gists";

    public static String ISSUES = "/issues/{id}";
    public static String PULLS = "/pulls/{id}";
    public static String CONTRIBUTORS = "/contributors";
    public static String HOOKS = "/hooks";
    public static String COLLABORATORS = "/collaborators";
    public static String LANGUAGES = "/languages";
    public static String FORKS = "/forks";
    private HashMap<String, GitHubRepo> repositories = new HashMap<>();
    private HashMap<GitHubRepo, Long> expirationDates = new HashMap<>();
    private ArrayList<GitHubIssue> issues = new ArrayList<>();
    public final RefreshTask TASK;

    public GitHub() {
        TASK = new RefreshTask();
        new Timer(true).schedule(TASK, 0, 90000);
    }

    public static String getRepoApiUrl(String repoName) {
        return REPO_API_URL.replace("{name}", repoName);
    }

    public static String getIssuesUrl(String repoName, int id) {
        return getRepoApiUrl(repoName) + ISSUES.replace("{id}", String.valueOf(id));
    }

    public static String getPullsUrl(String repoName, int id) {
        return getRepoApiUrl(repoName) + PULLS.replace("{id}", String.valueOf(id));
    }

    public static String getContributorsUrl(String repoName) {
        return getRepoApiUrl(repoName) + CONTRIBUTORS;
    }

    public static String getHooksUrl(String repoName) {
        return getRepoApiUrl(repoName) + HOOKS;
    }

    public static String getCollaboratorsUrl(String repoName) {
        return getRepoApiUrl(repoName) + COLLABORATORS;
    }

    public static String getLanguagesUrl(String repoName) {
        return getRepoApiUrl(repoName) + LANGUAGES;
    }

    public static String getForksUrl(String repoName) {
        return getRepoApiUrl(repoName) + FORKS;
    }

    public static String getUserUrl(String userLogin) {
        return USER_API_URL.replace("{name}", userLogin);
    }

    public static GitHub getGitHub() {
        return Nexus.getInstance().getGithub();
    }

    public String createGist(Exception e) {
        Writer writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);
        e.printStackTrace(printWriter);
        Gist gist = new Gist(new GistFile(writer.toString()));
        return gist.create();
    }

    public String getAccessToken(String userLogin, boolean onlyAllowTokenAccess) {
        if (userLogin == null || userLogin.isEmpty()) {
            return Nexus.getInstance().getGitHubConfig().getNexusGitHubApiKey();
        }
        String accessToken = Nexus.getInstance().getGitHubConfig().getGitHubApiKey(userLogin);
        if (accessToken.isEmpty() && onlyAllowTokenAccess) {
            throw new GitHubAPIKeyInvalidException("Please provide a GitHub API key (via the ghkey command) to access this part of the GitHub API");
        }
        // Make sure that we have a valid API key to use. Provide the default Nexus API key if this user doesn't have one.
        return accessToken.isEmpty() ? Nexus.getInstance().getGitHubConfig().getNexusGitHubApiKey() : accessToken;
    }

    public String getAccessToken(String userLogin) {
        return getAccessToken(userLogin, false);
    }

    private GitHubRateLimit getApiRateLimit(String accessToken) {
        try {
            return JsonUtil.read(Unirest.get(RATE_LIMIT_API_URL + (accessToken.startsWith("?access_token=") ? accessToken : (accessToken.isEmpty() ? "" : "?access_token=" + accessToken))), "rate", GitHubRateLimit.class);
        } catch (UnirestException e) {
            throw new GitHubException("Failed to connect to GitHub API!", e);
        }
    }

    public HttpResponse<JsonNode> makeRequest(String urlPath, String userLogin) throws UnirestException {
        return makeRequest(urlPath, userLogin, false);
    }

    protected HttpResponse<JsonNode> makeRequest(String urlPath, String userLogin, boolean assumeAccess) throws UnirestException {
        return makeRequest(urlPath, userLogin, assumeAccess, false);
    }

    protected HttpResponse<JsonNode> makeRequest(String urlPath, String userLogin, boolean assumeAccess, boolean onlyAllowTokenAccess) throws UnirestException {
        String accessToken = getAccessToken(userLogin, onlyAllowTokenAccess);
        // check if the api key has expired - overused
        if (getApiRateLimit(accessToken).getRemaining() <= 0) {
            throw new GitHubRateLimitExceededException("Rate limit for GitHub API exceeded. Further requests cannot be executed.");
        }

        Nexus.LOGGER.info("Connecting to " + urlPath + " with ACCESS_TOKEN of " + userLogin);
        HttpResponse<JsonNode> response = Unirest.get(urlPath).header("Authorization", "token " + accessToken).asJson();
        if (!assumeAccess) {
            try {
                String checkAccess = response.getBody().getObject().getString("message");
                if (checkAccess != null && checkAccess.equalsIgnoreCase("BAD CREDENTIALS")) {
                    throw new GitHubAPIKeyInvalidException("Invalid GitHub API key!");
                }
                return response;
            } catch (JSONException ignored) {
            } catch (NullPointerException ignored) {
            }
        }
        return response;
    }

    public GitHubRepo getRepo(String name, String userLogin) {
        Nexus.LOGGER.info("Requesting GitHub repo (" + name + ") on behalf of " + userLogin);
        if (repositories.get(name) != null) {
            return repositories.get(name);
        }
        try {
            HttpResponse<JsonNode> response = makeRequest(getRepoApiUrl(name), userLogin);
            InputStream input = response.getRawBody();
            GitHubRepo repo = JsonUtil.read(input, GitHubRepo.class);
            if (repo == null || repo.getUrl() == null) {
                throw new GitHubNotFoundException("Failed to locate GitHub Repo: " + name);
            }
            repo.repoOwner = getUser(response.getBody().getObject().getJSONObject("owner").getString("login"), userLogin);
            repo.collaborators = getCollaborators(repo, userLogin);
            repo.contributors = getContributors(repo, userLogin);
            repo.languages = getLanguages(repo, userLogin);
            repo.userLoginForAccessToken = userLogin;
            if (repo != null) {
                cache(repo);
            }
            return repo;
        } catch (UnirestException e) {
            if (e.getCause() instanceof FileNotFoundException) {
                throw new GitHubNotFoundException("Failed to locate GitHub Repo: " + name, e);
            }
            throw new GitHubException("Error connecting to GitHub API! ", e);
        }
    }

    public void fork(GitHubRepo repo, String userLogin) {
        Nexus.LOGGER.info("Attempting to fork repo (" + repo.getFullName() + ") on behalf of " + userLogin);
        try {
            Unirest.post(getForksUrl(repo.getFullName())).header("Authorization", "token " + getAccessToken(userLogin, true)).asJson();
        } catch (UnirestException e) {
            throw new GitHubException("Error connecting to GitHub API! ", e);
        }
    }

    public void forkAsync(GitHubRepo repo, String userLogin, Callback<JsonNode> callback) {
        Nexus.LOGGER.info("Attempting to fork repo (" + repo.getFullName() + ") on behalf of " + userLogin);
        Unirest.post(getForksUrl(repo.getFullName())).header("Authorization", "token " + getAccessToken(userLogin, true)).asJsonAsync(callback);
    }

    public GitHubUser getUser(String ghUserLogin, String userLogin) {
        Nexus.LOGGER.info("Requesting GitHub user (" + ghUserLogin + ") on behalf of " + userLogin);
        try {
            return JsonUtil.read(makeRequest(getUserUrl(ghUserLogin), userLogin).getRawBody(), GitHubUser.class);
        } catch (UnirestException e) {
            if (e.getCause() instanceof FileNotFoundException) {
                throw new GitHubUserNotFoundException("Failed to locate GitHub User: " + ghUserLogin, e);
            }
            throw new GitHubException("Error connecting to GitHub API! ", e);
        }
    }

    protected GitHubUser getReporterOf(GitHubIssue issue, String userLogin) {
        try {
            String issueUrl = issue instanceof GitHubPullRequest ? getPullsUrl(issue.getRepo().getFullName(), issue.getNumber()) : getIssuesUrl(issue.getRepo().getFullName(), issue.getNumber());
            HttpResponse<JsonNode> response = makeRequest(issueUrl, userLogin);
            return getUser(response.getBody().getObject().getJSONObject("user").getString("login"), userLogin);
        } catch (UnirestException e) {
            if (e.getCause() instanceof FileNotFoundException) {
                throw new GitHubNotFoundException("Failed to locate GitHub Repo: " + issue.getRepo().getFullName(), e);
            }
            throw new GitHubException("Error connecting to GitHub API! ", e);
        }
    }

    public GitHubIssue getIssue(GitHubRepo repo, int id, String userLogin) {
        Nexus.LOGGER.info("Requesting GitHub issue (" + repo.getFullName() + " - #" + id + ") on behalf of " + userLogin);
        Iterator<GitHubIssue> iter = new ArrayList<>(issues).iterator();
        while (iter.hasNext()) {
            GitHubIssue i = iter.next();
            if (repo.getFullName().equals(i.getRepo().getFullName()) && i.getNumber() == id) {
                return i;
            }
        }

        try {
            HttpResponse<JsonNode> response = makeRequest(getIssuesUrl(repo.getFullName(), id), userLogin);
            InputStream input = response.getRawBody();
            GitHubIssue issue = null;
            boolean checkForPullRequest = false;
            try {
                if (response.getBody().getObject().get("pull_request") != null) {
                    checkForPullRequest = true;
                } else {
                }
            } catch (JSONException e) {
            }
            try {
                if (!checkForPullRequest) {
                    issue = JsonUtil.read(input, GitHubIssue.class);
                }
                if (issue == null || issue.getNumber() <= 0) {
                    issue = JsonUtil.read(makeRequest(getPullsUrl(repo.getFullName(), id), userLogin).getRawBody(), GitHubPullRequest.class);
                }
            } catch (JSONException e) {
            }
            if (issue.getNumber() <= 0) {
                throw new GitHubNotFoundException("Issue #" + id + " doesn't exist at " + repo.getFullName());
            }
            issue.repo = repo;
            issue.reportedBy = getReporterOf(issue, userLogin);
            if (issue != null) {
                cache(issue);
            }
            return issue;
        } catch (UnirestException e) {
            if (e.getCause() instanceof FileNotFoundException) {
                throw new GitHubNotFoundException("Failed to locate GitHub Repo: " + repo.getFullName(), e);
            }
            throw new GitHubException("Error connecting to GitHub API! ", e);
        }
    }

    public GitHubIssue getIssue(String repoName, int id, String userLogin) {
        return getIssue(getRepo(repoName, userLogin), id, userLogin);
    }

    public void mergePullRequest(GitHubPullRequest pullRequest, String userLogin) {
        Nexus.LOGGER.info("Attempting to merge pull request (" + pullRequest.getRepo().getFullName() + " #" + pullRequest.getNumber() + ") on behalf of " + userLogin);
        try {
            HttpResponse<JsonNode> response = Unirest.put(pullRequest.getApiUrl() + "/merge").header("Authorization", "token " + getAccessToken(userLogin, true)).body("{\"commit_message\":\"" + pullRequest.getTitle() + "\"}").asJson();
            JSONObject responseObject = response.getBody().getObject();
            String message = responseObject.getString("message");
            boolean mergeStatus = false;
            try {
                mergeStatus = responseObject.getBoolean("merged");
            } catch (JSONException e) {
            }
            if (!mergeStatus) {
                if (message.equalsIgnoreCase("NOT FOUND")) {
                    message = "You do not have access to this";
                }
                throw new GitHubPullRequestMergeException(message);
            }
        } catch (UnirestException e) {
            throw new GitHubException("Error connecting to GitHub API! ", e);
        }
    }

    protected GitHubUser[] getCollaborators(GitHubRepo repo, String userLogin) {
        return getCollaborators(repo.getFullName(), userLogin);
    }

    protected GitHubUser[] getCollaborators(String name, String userLogin) {
        try {
            return JsonUtil.read(makeRequest(getCollaboratorsUrl(name), userLogin, true).getRawBody(), GitHubUser[].class);
        } catch (UnirestException e) {
            if (e.getCause() instanceof FileNotFoundException) {
                throw new GitHubNotFoundException("Failed to locate GitHub Repo: " + name, e);
            }
            throw new GitHubException("Error connecting to GitHub API! ", e);
        }
    }

    protected GitHubUser[] getContributors(GitHubRepo repo, String userLogin) {
        return getContributors(repo.getFullName(), userLogin);
    }

    protected GitHubUser[] getContributors(String name, String userLogin) {
        try {
            // Anything using this method should already have checked API key validity
            return JsonUtil.read(makeRequest(getContributorsUrl(name), userLogin, true).getRawBody(), GitHubUser[].class);
        } catch (UnirestException e) {
            if (e.getCause() instanceof FileNotFoundException) {
                throw new GitHubNotFoundException("Failed to locate GitHub Repo: " + name, e);
            }
            throw new GitHubException("Error connecting to GitHub API! ", e);
        } catch (NullPointerException ignored) {
            // in the event that there's no collaborators
            return new GitHubUser[0];
        }
    }

    protected GitHubLanguage[] getLanguages(GitHubRepo repo, String userLogin) {
        return getLanguages(repo.getFullName(), userLogin);
    }

    protected GitHubLanguage[] getLanguages(String name, String userLogin) {
        try {
            // Anything using this method should already have checked API key validity
            JSONObject jsonResponse = makeRequest(getLanguagesUrl(name), userLogin).getBody().getObject();
            Set<String> set = jsonResponse.keySet();
            ArrayList<GitHubLanguage> languages = new ArrayList<>();
            for (String language : set) {
                languages.add(new GitHubLanguage(language, jsonResponse.getInt(language)));
            }
            return languages.toArray(new GitHubLanguage[languages.size()]);
        } catch (UnirestException e) {
            if (e.getCause() instanceof FileNotFoundException) {
                throw new GitHubNotFoundException("Failed to locate GitHub Repo: " + name, e);
            }
            throw new GitHubException("Error connecting to GitHub API! ", e);
        }
    }

    public GitHubHook[] getHooks(GitHubRepo repo, String userLogin) {
        return getHooks(repo.getFullName(), userLogin);
    }

    public GitHubHook[] getHooks(String name, String userLogin) {
        Nexus.LOGGER.info("Requesting GitHub hooks for " + name + " on behalf of " + userLogin);
        try {
            return JsonUtil.read(makeRequest(getHooksUrl(name), userLogin, false, true).getRawBody(), GitHubHook[].class);
        } catch (UnirestException e) {
            if (e.getCause() instanceof FileNotFoundException) {
                throw new GitHubNotFoundException("Failed to locate GitHub Repo: " + name, e);
            }
            throw new GitHubException("Error connecting to GitHub API! ", e);
        }
    }

    public GitHubHook getHook(GitHubRepo repo, int id, String userLogin) {
        return getHook(repo.getFullName(), id, userLogin);
    }

    public GitHubHook getHook(String repo, int id, String userLogin) {
        for (GitHubHook hook : getHooks(repo, userLogin)) {
            if (hook.getId() == id) {
                return hook;
            }
        }
        return null;
    }

    public GitHubHook getHook(GitHubRepo repo, String name, String userLogin) {
        return getHook(repo.getFullName(), name, userLogin);
    }

    public GitHubHook getHook(String repo, String name, String userLogin) {
        for (GitHubHook hook : getHooks(repo, userLogin)) {
            if (hook.getName().equals(name)) {
                return hook;
            }
        }
        return null;
    }

    public void setIrcNotifications(GitHubRepo repo, String userLogin, GitHubEvent... events) {
        setIrcNotifications(repo.getFullName(), userLogin, events);
    }

    public void setIrcNotifications(String repo, String userLogin, GitHubEvent... events) {
        GitHubHook hook = getHook(repo, "irc", userLogin);
        if (hook != null) {
            String s = "";
            for (GitHubEvent e : events) {
                s += s.isEmpty() ? "\"" + e.getJsonName() + "\"" : "," + "\"" + e.getJsonName() + "\"";
            }
            Nexus.LOGGER.info("Attempting to set IRC notifications for " + repo + " to " + s + " on behalf of " + userLogin);
            ArrayList<String> eventsList = new ArrayList<>();
            for (GitHubEvent e : events) {
                eventsList.add(e.getJsonName());
            }
            try {
                Unirest.patch(getHooksUrl(repo) + "/" + hook.getId())
                        .header("Authorization", "token " + getAccessToken(userLogin, true))
                        .header("accept", "application/json")
                        .header("content-type", "application/json")
                        .body("{\"events\":[" + s + "]}").asJson();
            } catch (UnirestException e) {
                throw new GitHubException("Could not connect to GitHub API!", e);
            }
        } else {
            throw new GitHubHookNotFoundException("IRC Hook not found for GitHub Repo (" + repo + ")");
        }
    }

    public GitHubEvent[] getIrcNotifications(GitHubRepo repo, String userLogin) {
        return getIrcNotifications(repo.getFullName(), userLogin);
    }

    public GitHubEvent[] getIrcNotifications(String repo, String userLogin) {
        GitHubHook hook = getHook(repo, "irc", userLogin);
        if (hook != null) {
            try {
                Nexus.LOGGER.info("Requesting GitHub IRC notification settings for " + repo + " on behalf of " + userLogin);
                ArrayList<GitHubEvent> events = new ArrayList<>();
                JSONArray eventsJsonArray = makeRequest(getHooksUrl(repo) + "/" + hook.getId(), userLogin, false, true).getBody().getObject().getJSONArray("events");
                for (int i = 0; i < eventsJsonArray.length(); i++) {
                    GitHubEvent e = GitHubEvent.getByJsonName(eventsJsonArray.getString(i));
                    if (e != null) {
                        events.add(e);
                    }
                }
                return events.toArray(new GitHubEvent[events.size()]);
            } catch (UnirestException e) {
                throw new GitHubException("Could not connect to GitHub API!", e);
            }
        } else {
            throw new GitHubHookNotFoundException("IRC Hook not found for GitHub Repo (" + repo + ")");
        }
    }

    private void cache(GitHubRepo repo) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MINUTE, 15);
        repositories.put(repo.getFullName(), repo);
        expirationDates.put(repo, c.getTimeInMillis());
    }

    private void cache(GitHubIssue issue) {
        issues.add(issue);
    }

    public class RefreshTask extends TimerTask {

        @Override
        public void run() {
            HashMap<String, GitHubRepo> fullNameToRepoMapCopy = new HashMap<>(repositories);
            ArrayList<GitHubIssue> issuesCopy = new ArrayList<>(issues);
            if (!fullNameToRepoMapCopy.isEmpty() || !issuesCopy.isEmpty()) {
                Nexus.LOGGER.info("Updating GitHub repo storage...");
                for (Map.Entry<String, GitHubRepo> entry : fullNameToRepoMapCopy.entrySet()) {
                    long expiration = expirationDates.get(entry.getValue());
                    if (expiration > 0) {
                        repositories.remove(entry.getKey());
                        expirationDates.remove(entry.getValue());
                        // Only keep them in memory for a certain period of time
                        if (new Date().before(new Date(expiration))) {
                            GitHubRepo repo = getRepo(entry.getKey(), entry.getValue().userLoginForAccessToken);
                            cache(repo);
                        }
                    }
                }

                for (GitHubIssue issue : issuesCopy) {
                    issues.remove(issue);
                    cache(getIssue(issue.getRepo(), issue.getNumber(), issue.repo.userLoginForAccessToken));
                }
            }
        }
    }
}
