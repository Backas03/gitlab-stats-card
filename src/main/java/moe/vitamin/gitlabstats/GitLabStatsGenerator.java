import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class GitLabStatsGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        String baseUrl = getenvOrDefault("GITLAB_BASE_URL", "https://gitlab.com");
        String token = requireEnv("GITLAB_TOKEN");
        String username = requireEnv("GITLAB_USERNAME");
        String outputPath = getenvOrDefault("OUTPUT_PATH", "assets/gitlab-stats.svg");

        baseUrl = stripTrailingSlash(baseUrl);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        List<JsonNode> events = fetchAllUserEvents(client, baseUrl, token, username);

        List<JsonNode> pushEvents = events.stream()
                .filter(GitLabStatsGenerator::isPushLikeEvent)
                .filter(e -> {
                    String authorUsername = e.path("author_username").asText("");
                    String nestedAuthorUsername = e.path("author").path("username").asText("");
                    return username.equalsIgnoreCase(authorUsername)
                            || username.equalsIgnoreCase(nestedAuthorUsername);
                })
                .toList();

        int commitCount = pushEvents.stream()
                .mapToInt(e -> e.path("push_data").path("commit_count").asInt(0))
                .sum();

        int pushEventCount = pushEvents.size();

        long distinctProjects = pushEvents.stream()
                .map(e -> e.path("project_id").asLong(-1))
                .filter(id -> id != -1)
                .distinct()
                .count();

        int mergeRequestCount = fetchMergeRequestCount(client, baseUrl, token, username);

        String lastActivity = pushEvents.stream()
                .map(e -> e.path("created_at").asText(""))
                .filter(s -> !s.isBlank())
                .max(Comparator.naturalOrder())
                .map(GitLabStatsGenerator::toRelativeTime)
                .orElse("No recent activity");

        String lastCommitTitle = pushEvents.stream()
                .max(Comparator.comparing(e -> e.path("created_at").asText("")))
                .map(e -> e.path("push_data").path("commit_title").asText(""))
                .filter(s -> !s.isBlank())
                .orElse("No recent commit");

        String generatedAt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        String svg = buildSvg(
                username,
                commitCount,
                pushEventCount,
                mergeRequestCount,
                distinctProjects,
                lastCommitTitle,
                lastActivity,
                generatedAt
        );

        Path output = Path.of(outputPath);
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, svg, StandardCharsets.UTF_8);

        System.out.println("GitLab base URL: " + baseUrl);
        System.out.println("Username: " + username);
        System.out.println("Total events fetched: " + events.size());
        System.out.println("Push-like events: " + pushEventCount);
        System.out.println("Commits counted: " + commitCount);
        System.out.println("Merge requests counted: " + mergeRequestCount);
        System.out.println("Projects counted: " + distinctProjects);
        System.out.println("Last commit: " + lastCommitTitle);
        System.out.println("Last activity: " + lastActivity);
        System.out.println("Output written to: " + output.toAbsolutePath());
    }

    private static List<JsonNode> fetchAllUserEvents(
            HttpClient client,
            String baseUrl,
            String token,
            String username
    ) throws Exception {
        List<JsonNode> all = new ArrayList<>();
        String encodedUsername = URLEncoder.encode(username, StandardCharsets.UTF_8);

        for (int page = 1; page <= 100; page++) {
            String path = "/api/v4/users/" + encodedUsername + "/events?per_page=100&page=" + page;
            String body = get(client, baseUrl, token, path);

            JsonNode arr = MAPPER.readTree(body);
            if (!arr.isArray() || arr.isEmpty()) {
                break;
            }

            arr.forEach(all::add);

            if (arr.size() < 100) {
                break;
            }
        }

        return all;
    }

    private static int fetchMergeRequestCount(
            HttpClient client,
            String baseUrl,
            String token,
            String username
    ) throws Exception {
        int total = 0;
        String encodedUsername = URLEncoder.encode(username, StandardCharsets.UTF_8);

        for (int page = 1; page <= 100; page++) {
            String path = "/api/v4/merge_requests?author_username=" + encodedUsername + "&per_page=100&page=" + page;
            String body = get(client, baseUrl, token, path);

            JsonNode arr = MAPPER.readTree(body);
            if (!arr.isArray() || arr.isEmpty()) {
                break;
            }

            total += arr.size();

            if (arr.size() < 100) {
                break;
            }
        }

        return total;
    }

    private static boolean isPushLikeEvent(JsonNode event) {
        JsonNode pushData = event.path("push_data");
        if (pushData.isMissingNode() || pushData.isNull()) {
            return false;
        }

        String actionName = event.path("action_name").asText("").toLowerCase();
        if (actionName.startsWith("pushed")) {
            return true;
        }

        String pushAction = pushData.path("action").asText("").toLowerCase();
        return "pushed".equals(pushAction) || "created".equals(pushAction);
    }

    private static String get(
            HttpClient client,
            String baseUrl,
            String token,
            String pathAndQuery
    ) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + pathAndQuery))
                .timeout(Duration.ofSeconds(30))
                .header("PRIVATE-TOKEN", token)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "GitLab API call failed: "
                            + response.statusCode()
                            + " / "
                            + pathAndQuery
                            + "\n"
                            + response.body()
            );
        }

        return response.body();
    }

    private static String toRelativeTime(String isoTime) {
        Instant then = OffsetDateTime.parse(isoTime).toInstant();
        Duration duration = Duration.between(then, Instant.now());

        long days = duration.toDays();
        if (days > 0) {
            return days + "d ago";
        }

        long hours = duration.toHours();
        if (hours > 0) {
            return hours + "h ago";
        }

        long minutes = duration.toMinutes();
        if (minutes > 0) {
            return minutes + "m ago";
        }

        return "just now";
    }

    private static String buildSvg(
            String username,
            int commitCount,
            int pushEventCount,
            int mergeRequestCount,
            long distinctProjects,
            String lastCommitTitle,
            String lastActivity,
            String generatedAt
    ) {
        String safeUsername = escapeXml(username);
        String safeLastCommitTitle = escapeXml(truncate(lastCommitTitle, 42));
        String safeLastActivity = escapeXml(lastActivity);
        String safeGeneratedAt = escapeXml(generatedAt);

        return """
                <svg width="495" height="270" viewBox="0 0 495 270" fill="none" xmlns="http://www.w3.org/2000/svg" role="img" aria-labelledby="title desc">
                  <title id="title">GitLab Stats</title>
                  <desc id="desc">GitLab stats card for %s</desc>

                  <style>
                    .bg { fill: #0d1117; }
                    .card { fill: #161b22; stroke: #30363d; stroke-width: 1; }

                    .title { fill: #e6edf3; font: 700 20px -apple-system,BlinkMacSystemFont,"Segoe UI",Helvetica,Arial,sans-serif; }
                    .sub { fill: #f1c40f; font: 400 13px -apple-system,BlinkMacSystemFont,"Segoe UI",Helvetica,Arial,sans-serif; }

                    .label { font: 600 12px -apple-system,BlinkMacSystemFont,"Segoe UI",Helvetica,Arial,sans-serif; }
                    .value { fill: #f0f6fc; font: 700 24px -apple-system,BlinkMacSystemFont,"Segoe UI",Helvetica,Arial,sans-serif; }

                    .commit { fill: #58a6ff; }
                    .push { fill: #a371f7; }
                    .mr { fill: #f778ba; }
                    .project { fill: #3fb950; }

                    .divider { stroke: #30363d; stroke-width: 1; }

                    .metaLabel { fill: #7d8590; font: 600 12px -apple-system,BlinkMacSystemFont,"Segoe UI",Helvetica,Arial,sans-serif; }
                    .metaValue { fill: #c9d1d9; font: 400 10px -apple-system,BlinkMacSystemFont,"Segoe UI",Helvetica,Arial,sans-serif; }

                    .foot { fill: #7d8590; font: 400 10px -apple-system,BlinkMacSystemFont,"Segoe UI",Helvetica,Arial,sans-serif; }
                  </style>

                  <rect class="bg" x="0" y="0" width="495" height="270" rx="16"/>
                  <rect class="card" x="10" y="10" width="475" height="250" rx="14"/>

                  <text class="title" x="28" y="42">GitLab Activity</text>
                  <text class="sub" x="28" y="64">@%s</text>

                  <text class="label commit" x="28" y="104">Commits</text>
                  <text class="value" x="28" y="132">%d</text>

                  <text class="label push" x="250" y="104">Push Events</text>
                  <text class="value" x="250" y="132">%d</text>

                  <text class="label mr" x="28" y="170">Merge Requests</text>
                  <text class="value" x="28" y="198">%d</text>

                  <text class="label project" x="250" y="170">Projects</text>
                  <text class="value" x="250" y="198">%d</text>

                  <line class="divider" x1="28" y1="214" x2="467" y2="214"/>

                  <text class="metaLabel" x="28" y="232">Last commit:</text>
                  <text class="metaValue" x="105" y="232">%s</text>

                  <text class="metaLabel" x="28" y="248">Last activity:</text>
                  <text class="metaValue" x="105" y="248">%s</text>

                  <text class="foot" x="467" y="251" text-anchor="end">Updated at %s</text>
                </svg>
                """.formatted(
                safeUsername,
                safeUsername,
                commitCount,
                pushEventCount,
                mergeRequestCount,
                distinctProjects,
                safeLastCommitTitle,
                safeLastActivity,
                safeGeneratedAt
        );
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String requireEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + key);
        }
        return value;
    }

    private static String getenvOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    private static String stripTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}