package com.kiraworld.sarahtravel;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.ClearTokenRequest;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Metadata-only periodic check. It never launches UI, reads a body or changes
 * a Gmail message. A new consent resolution disables execution until the
 * person opens Sarah and explicitly reconnects.
 */
public final class GmailTravelMonitorWorker extends Worker {
    public GmailTravelMonitorWorker(
            @NonNull Context appContext,
            @NonNull WorkerParameters parameters) {
        super(appContext, parameters);
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        String profileId = getInputData().getString(
                GmailMonitorScheduler.INPUT_PROFILE_ID);
        profileId = profileId == null ? "" : profileId.trim();
        GmailTokenVault vault = new GmailTokenVault(context);
        if (profileId.isEmpty()
                || !vault.monitoringEnabled(profileId)
                || !ConfirmedOwnerLease.isExactActiveOwner(context, profileId)) {
            return Result.success();
        }
        try {
            AuthorizationRequest request = AuthorizationRequest.builder()
                    .setRequestedScopes(Collections.singletonList(
                            new Scope(GmailReadOnlyPolicy.SCOPE)))
                    .setOptOutIncludingGrantedScopes(true)
                    .build();
            AuthorizationResult authorization = Tasks.await(
                    Identity.getAuthorizationClient(context).authorize(request),
                    25L,
                    TimeUnit.SECONDS);
            if (authorization == null || authorization.hasResolution()) {
                vault.clearCachedToken(true);
                vault.recordSyncStatus("OWNER_REAUTHORIZATION_REQUIRED", System.currentTimeMillis());
                return Result.success();
            }
            if (!GmailReadOnlyPolicy.exactReadOnlyGrant(
                    authorization.getGrantedScopes())) {
                vault.clearCachedToken(true);
                vault.recordSyncStatus("READONLY_SCOPE_NOT_GRANTED", System.currentTimeMillis());
                return Result.failure();
            }
            String token = authorization.getAccessToken();
            long now = System.currentTimeMillis();
            String observedEmail = GmailReadOnlyClient.fetchAccountEmail(token);
            String expectedEmail = vault.accountEmail(profileId);
            if (expectedEmail.isEmpty() || !expectedEmail.equalsIgnoreCase(observedEmail)) {
                vault.clearCachedToken(true);
                vault.setMonitoringEnabled(profileId, false);
                vault.recordSyncStatus("ACCOUNT_CHANGED_OWNER_REVIEW_REQUIRED", now);
                return Result.failure();
            }
            vault.saveAuthorizedAccess(token, observedEmail, profileId, now);
            List<GmailReadOnlyClient.SourceReceipt> receipts =
                    GmailReadOnlyClient.findTravelCandidates(
                            token, observedEmail, now);
            if (receipts.isEmpty()) {
                vault.recordSyncStatus("READ_ONLY_NO_TRAVEL_CANDIDATES", now);
            } else {
                boolean proposalNotificationPosted = false;
                for (GmailReadOnlyClient.SourceReceipt receipt : receipts) {
                    if (vault.recordRead(profileId, receipt)
                            && !proposalNotificationPosted) {
                        EmailCandidateNotificationManager.postProposal(
                                context, receipt.messageId, receipt.subject);
                        proposalNotificationPosted = true;
                    }
                }
            }
            return Result.success();
        } catch (GmailReadOnlyClient.UnauthorizedException unauthorized) {
            String invalidToken = vault.usableAccessToken(
                    profileId, System.currentTimeMillis());
            vault.clearCachedToken(true);
            vault.recordSyncStatus("AUTHORIZATION_REJECTED", System.currentTimeMillis());
            if (!invalidToken.isEmpty()) {
                try {
                    Tasks.await(
                            Identity.getAuthorizationClient(context).clearToken(
                                    ClearTokenRequest.builder()
                                            .setToken(invalidToken)
                                            .build()),
                            10L,
                            TimeUnit.SECONDS);
                } catch (Exception ignored) { }
            }
            return Result.success();
        } catch (Exception error) {
            vault.recordSyncStatus("BOUNDED_CHECK_FAILED", System.currentTimeMillis());
            return getRunAttemptCount() < 2 ? Result.retry() : Result.success();
        }
    }
}
