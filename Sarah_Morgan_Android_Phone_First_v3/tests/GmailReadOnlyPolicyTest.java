package com.kiraworld.sarahtravel;

import java.util.List;

public final class GmailReadOnlyPolicyTest {
    public static void main(String[] args) {
        require(GmailReadOnlyPolicy.exactReadOnlyGrant(
                List.of(GmailReadOnlyPolicy.SCOPE)), "exact read-only grant");
        require(!GmailReadOnlyPolicy.exactReadOnlyGrant(
                List.of("https://www.googleapis.com/auth/gmail.modify")),
                "reject Gmail modify");
        require(!GmailReadOnlyPolicy.exactReadOnlyGrant(
                List.of(GmailReadOnlyPolicy.SCOPE,
                        "https://www.googleapis.com/auth/gmail.send")),
                "reject mixed write grant");
        require(!GmailReadOnlyPolicy.exactReadOnlyGrant(
                List.of(GmailReadOnlyPolicy.SCOPE,
                        "https://www.googleapis.com/auth/drive.file")),
                "reject any broader mixed grant");
        require(!GmailReadOnlyPolicy.exactReadOnlyGrant(
                List.of("https://mail.google.com/")), "reject full mailbox scope");
        require(GmailReadOnlyPolicy.permittedRequest(
                "GET", "https://gmail.googleapis.com/gmail/v1/users/me/profile"),
                "profile GET allowed");
        require(GmailReadOnlyPolicy.permittedRequest(
                "GET", "https://gmail.googleapis.com/gmail/v1/users/me/messages?q=x"),
                "message GET allowed");
        require(!GmailReadOnlyPolicy.permittedRequest(
                "POST", "https://gmail.googleapis.com/gmail/v1/users/me/messages/send"),
                "send forbidden");
        require(!GmailReadOnlyPolicy.permittedRequest(
                "GET", "https://gmail.googleapis.com/gmail/v1/users/me/messages/id/modify"),
                "write-shaped endpoint forbidden even with GET");
        require(!GmailReadOnlyPolicy.permittedRequest(
                "GET", "https://gmail.googleapis.com/gmail/v1/users/me/messages/id/trash"),
                "trash endpoint forbidden even with GET");
        require(!GmailReadOnlyPolicy.permittedRequest(
                "DELETE", "https://gmail.googleapis.com/gmail/v1/users/me/messages/id"),
                "delete forbidden");
        require(GmailReadOnlyPolicy.travelQuery().contains("-in:spam"),
                "spam excluded");
        require(GmailReadOnlyPolicy.travelQuery().contains("newer_than:365d"),
                "bounded time window");
        require(GmailReadOnlyPolicy.MAX_CANDIDATES == 10,
                "bounded candidate count");
        require(GmailReadOnlyPolicy.metadataHeaders().equals(
                List.of("Subject", "From", "Date")), "metadata-first headers");
        require(GmailReadOnlyPolicy.usableCachedToken(
                "token", GmailReadOnlyPolicy.SCOPE, 100_000L, 1_000L),
                "fresh token accepted");
        require(!GmailReadOnlyPolicy.usableCachedToken(
                "token", GmailReadOnlyPolicy.SCOPE, 20_000L, 1_000L),
                "near-expiry token rejected");
        System.out.println("GMAIL_READONLY_POLICY_PASS");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
