package com.kiraworld.sarahtravel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal GET-only Gmail REST client with bounded metadata responses. */
public final class GmailReadOnlyClient {
    private static final String PROFILE_ENDPOINT =
            "https://gmail.googleapis.com/gmail/v1/users/me/profile";
    private static final String MESSAGES_ENDPOINT =
            "https://gmail.googleapis.com/gmail/v1/users/me/messages";

    private GmailReadOnlyClient() { }

    public static String fetchAccountEmail(String accessToken) throws Exception {
        JSONObject profile = getJson(PROFILE_ENDPOINT, accessToken);
        String email = profile.optString("emailAddress", "").trim();
        if (email.isEmpty() || email.length() > 254 || !email.contains("@")) {
            throw new IllegalStateException("GMAIL_PROFILE_HAS_NO_ACCOUNT_EMAIL");
        }
        return email;
    }

    public static List<SourceReceipt> findTravelCandidates(
            String accessToken,
            String accountEmail,
            long nowMillis) throws Exception {
        String query = GmailReadOnlyPolicy.travelQuery();
        String listUrl = MESSAGES_ENDPOINT
                + "?maxResults=" + GmailReadOnlyPolicy.MAX_CANDIDATES
                + "&includeSpamTrash=false&q=" + encode(query)
                + "&fields=messages(id,threadId),resultSizeEstimate";
        JSONObject listed = getJson(listUrl, accessToken);
        JSONArray messages = listed.optJSONArray("messages");
        List<SourceReceipt> receipts = new ArrayList<>();
        if (messages == null) return receipts;
        int count = Math.min(messages.length(), GmailReadOnlyPolicy.MAX_CANDIDATES);
        for (int index = 0; index < count; index++) {
            String id = messages.optJSONObject(index) == null
                    ? "" : messages.optJSONObject(index).optString("id", "").trim();
            if (!id.matches("[A-Za-z0-9_-]{4,128}")) continue;
            // Gmail's METADATA format omits the message snippet. FULL plus a
            // partial-response fields mask returns header metadata and
            // Gmail's short preview; the client retains only Subject, From,
            // Date and the bounded preview. No MIME body/parts, attachment,
            // raw source or write operation is requested.
            String getUrl = MESSAGES_ENDPOINT + "/" + encode(id)
                    + "?format=full"
                    + "&fields=id,threadId,internalDate,snippet,payload(headers)";
            JSONObject message = getJson(getUrl, accessToken);
            JSONObject payload = message.optJSONObject("payload");
            JSONArray headers = payload == null ? null : payload.optJSONArray("headers");
            String subject = header(headers, "Subject");
            String sender = header(headers, "From");
            String date = header(headers, "Date");
            String snippet = clean(message.optString("snippet", ""), 1200);
            long internalDate = parseLong(message.optString("internalDate", ""));
            EmailCalendarPolicy.ExactTimes exactTimes =
                    EmailCalendarPolicy.exactTimesFromSourceText(subject, snippet);
            receipts.add(new SourceReceipt(
                    id,
                    message.optString("threadId", ""),
                    accountEmail,
                    subject,
                    sender,
                    date,
                    snippet,
                    internalDate,
                    nowMillis,
                    query,
                    getUrl.substring(0, getUrl.indexOf('?')),
                    "gmail.readonly_metadata_and_bounded_snippet",
                    EmailCalendarPolicy.candidateKind(subject, snippet),
                    exactTimes.startInstant,
                    exactTimes.endInstant,
                    exactTimes.sourceSupported));
        }
        return receipts;
    }

    private static JSONObject getJson(String endpoint, String accessToken) throws Exception {
        if (!GmailReadOnlyPolicy.permittedRequest("GET", endpoint)) {
            throw new SecurityException("GMAIL_REQUEST_OUTSIDE_READONLY_ALLOWLIST");
        }
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new UnauthorizedException("GMAIL_ACCESS_TOKEN_MISSING");
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(18_000);
            connection.setRequestProperty("Authorization", "Bearer " + accessToken.trim());
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-store");
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) {
                throw new UnauthorizedException("GMAIL_AUTHORIZATION_REJECTED_" + status);
            }
            if (status < 200 || status > 299) {
                readBounded(connection.getErrorStream());
                throw new IllegalStateException("GMAIL_READ_FAILED_HTTP_" + status);
            }
            return new JSONObject(new String(
                    readBounded(connection.getInputStream()), StandardCharsets.UTF_8));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] readBounded(InputStream input) throws Exception {
        if (input == null) return new byte[0];
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > GmailReadOnlyPolicy.MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("GMAIL_RESPONSE_TOO_LARGE");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String header(JSONArray headers, String wanted) {
        if (headers == null) return "";
        for (int index = 0; index < headers.length(); index++) {
            JSONObject header = headers.optJSONObject(index);
            if (header != null && wanted.equalsIgnoreCase(header.optString("name", ""))) {
                String value = header.optString("value", "").replaceAll("\\s+", " ").trim();
                return value.length() <= 300 ? value : value.substring(0, 300);
            }
        }
        return "";
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value); }
        catch (Exception ignored) { return 0L; }
    }

    private static String clean(String value, int limit) {
        String text = value == null ? "" : value
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "")
                .replaceAll("\\s+", " ").trim();
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    public static final class UnauthorizedException extends Exception {
        UnauthorizedException(String message) { super(message); }
    }

    public static final class SourceReceipt {
        public final String messageId;
        public final String threadId;
        public final String accountEmail;
        public final String subject;
        public final String sender;
        public final String messageDate;
        public final String boundedSnippet;
        public final long internalDateEpochMillis;
        public final long fetchedAtEpochMillis;
        public final String exactQuery;
        public final String sourceEndpoint;
        public final String accessMode;
        public final String candidateKind;
        public final String exactStartInstant;
        public final String exactEndInstant;
        public final boolean exactTimesSupportedBySource;

        SourceReceipt(
                String messageId,
                String threadId,
                String accountEmail,
                String subject,
                String sender,
                String messageDate,
                String boundedSnippet,
                long internalDateEpochMillis,
                long fetchedAtEpochMillis,
                String exactQuery,
                String sourceEndpoint,
                String accessMode,
                String candidateKind,
                String exactStartInstant,
                String exactEndInstant,
                boolean exactTimesSupportedBySource) {
            this.messageId = messageId;
            this.threadId = threadId;
            this.accountEmail = accountEmail;
            this.subject = subject;
            this.sender = sender;
            this.messageDate = messageDate;
            this.boundedSnippet = boundedSnippet;
            this.internalDateEpochMillis = internalDateEpochMillis;
            this.fetchedAtEpochMillis = fetchedAtEpochMillis;
            this.exactQuery = exactQuery;
            this.sourceEndpoint = sourceEndpoint;
            this.accessMode = accessMode;
            this.candidateKind = candidateKind;
            this.exactStartInstant = exactStartInstant;
            this.exactEndInstant = exactEndInstant;
            this.exactTimesSupportedBySource = exactTimesSupportedBySource;
        }

        JSONObject toJson() throws Exception {
            JSONObject value = new JSONObject();
            value.put("schema", "sarah-gmail-read-receipt-v1");
            value.put("message_id", messageId);
            value.put("thread_id", threadId);
            value.put("account_email", accountEmail);
            value.put("subject", subject);
            value.put("sender", sender);
            value.put("message_date", messageDate);
            value.put("bounded_snippet", boundedSnippet);
            value.put("internal_date_epoch_ms", internalDateEpochMillis);
            value.put("fetched_at_epoch_ms", fetchedAtEpochMillis);
            value.put("exact_query", exactQuery);
            value.put("source_endpoint", sourceEndpoint);
            value.put("access_mode", accessMode);
            value.put("body_read", false);
            value.put("bounded_snippet_read", true);
            value.put("message_modified", false);
            value.put("candidate_kind", candidateKind);
            value.put("email_candidate_state", EmailCalendarPolicy.EMAIL_PENDING);
            value.put("calendar_item_state", EmailCalendarPolicy.CALENDAR_NOT_SAVED);
            value.put("reminder_state", EmailCalendarPolicy.REMINDER_NOT_SCHEDULED);
            value.put("calendar_start_instant", exactStartInstant);
            value.put("calendar_end_instant", exactEndInstant);
            value.put("calendar_time_source", exactTimesSupportedBySource
                    ? EmailCalendarPolicy.TIME_SOURCE_EMAIL : "TIME_NOT_PRESENT_OR_AMBIGUOUS");
            value.put("source_supported_exact_times", exactTimesSupportedBySource);
            return value;
        }

        public String ownerLabel() {
            return GmailReadOnlyPolicy.safeReceiptLabel(subject, sender, messageDate)
                    + "\nSource: Gmail message " + messageId
                    + "\nChecked at: " + fetchedAtEpochMillis
                    + "\nRead: bounded metadata/snippet only; message unchanged"
                    + "\nCalendar: proposal only until the owner saves it";
        }
    }
}
