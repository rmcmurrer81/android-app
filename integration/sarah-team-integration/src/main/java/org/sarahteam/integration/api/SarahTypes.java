package org.sarahteam.integration.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Credential-free values shared across UI and transport implementations. */
public final class SarahTypes {
    private SarahTypes() { }

    public enum ConversationMode { ONLINE_PREFERRED, LOCAL_ONLY }

    public enum TurnRoute {
        ONLINE_WORKERS_AI,
        ONLINE_OPENAI,
        ONLINE_CONNECTED_OTHER,
        OFFLINE_LOCAL,
        ONLINE_FAILED_FELL_BACK_OFFLINE,
        LOCAL_TOOL_RESULT,
        PUBLIC_SOURCE_TOOL_RESULT,
        TOOL_UNAVAILABLE
    }

    public enum Classification {
        TRUTHFUL_STATEMENT,
        DELIBERATE_LIE,
        JOKE_OR_SARCASM,
        EVASION,
        PRIVACY_PROTECTION,
        SOFTENED_TRUTH,
        PARTIAL_TRUTH,
        EXAGGERATION,
        UNCERTAIN_BELIEF,
        SINCERE_MISTAKE,
        HALLUCINATION_OR_GROUNDING_ERROR,
        IDENTITY_ATTRIBUTION_ERROR,
        RUNTIME_STATE_ERROR
    }

    public enum LocationSource {
        DEVICE_RESOLVED_APPROXIMATE,
        OWNER_ENTERED_APPROXIMATE
    }

    public enum AccessState { READY, RENEWING, OFFLINE, EXPIRED, UNAVAILABLE }

    public enum VoiceRoute { PROTECTED_ELEVENLABS, OFFLINE_DEVICE, NONE }

    public enum ErrorCode {
        NONE,
        INVALID_REQUEST,
        PERSON_SCOPE_REQUIRED,
        LOCATION_REQUIRED,
        LOCATION_DENIED,
        AUTH_UNAVAILABLE,
        CURRENT_SOURCE_UNAVAILABLE,
        ONLINE_FAILED,
        VOICE_UNAVAILABLE,
        CANCELLED,
        TIMEOUT,
        HOST_FAILURE
    }

    public interface Completion<T> {
        void complete(T value);
        void failed(OperationError error);
    }

    public interface Cancellable {
        void cancel();
    }

    public static final Cancellable NO_OP_CANCELLABLE = new Cancellable() {
        @Override public void cancel() { }
    };

    public static final class OperationError {
        public final ErrorCode code;
        public final boolean retryable;
        public final String factualTruth;

        public OperationError(ErrorCode code, boolean retryable, String factualTruth) {
            this.code = code == null ? ErrorCode.HOST_FAILURE : code;
            this.retryable = retryable;
            this.factualTruth = clean(factualTruth);
        }

        public static OperationError none() {
            return new OperationError(ErrorCode.NONE, false, "No error occurred.");
        }
    }

    public static final class ApproximateArea {
        public final String label;
        public final LocationSource source;
        public final long capturedAtEpochMs;
        public final long expiresAtEpochMs;

        public ApproximateArea(
                String label,
                LocationSource source,
                long capturedAtEpochMs,
                long expiresAtEpochMs) {
            this.label = required(label, "Approximate area label");
            this.source = source == null
                    ? LocationSource.OWNER_ENTERED_APPROXIMATE : source;
            this.capturedAtEpochMs = capturedAtEpochMs;
            this.expiresAtEpochMs = expiresAtEpochMs;
        }

        public boolean isFresh(long nowEpochMs) {
            return capturedAtEpochMs > 0L
                    && expiresAtEpochMs > capturedAtEpochMs
                    && nowEpochMs >= capturedAtEpochMs
                    && nowEpochMs < expiresAtEpochMs;
        }
    }

    public static final class SourceReceipt {
        public final boolean applied;
        public final long observedAtEpochMs;
        public final String scope;
        public final List<String> sourceIds;

        public SourceReceipt(
                boolean applied,
                long observedAtEpochMs,
                String scope,
                List<String> sourceIds) {
            this.applied = applied;
            this.observedAtEpochMs = observedAtEpochMs;
            this.scope = clean(scope);
            this.sourceIds = immutableStrings(sourceIds);
            if (applied && this.sourceIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "An applied current-source receipt requires a source ID");
            }
        }

        public static SourceReceipt none(String scope, long observedAtEpochMs) {
            return new SourceReceipt(
                    false, observedAtEpochMs, scope, Collections.<String>emptyList());
        }
    }

    public static final class TurnRequest {
        public final String requestId;
        public final String personScopeId;
        public final long submittedAtEpochMs;
        public final String message;
        public final ConversationMode conversationMode;
        public final boolean currentSourcesRequested;
        public final ApproximateArea approximateArea;
        public final String conversationCursor;

        public TurnRequest(
                String requestId,
                String personScopeId,
                long submittedAtEpochMs,
                String message,
                ConversationMode conversationMode,
                boolean currentSourcesRequested,
                ApproximateArea approximateArea,
                String conversationCursor) {
            this.requestId = required(requestId, "Request ID");
            this.personScopeId = required(personScopeId, "Person scope ID");
            this.submittedAtEpochMs = submittedAtEpochMs;
            this.message = required(message, "Message");
            this.conversationMode = conversationMode == null
                    ? ConversationMode.ONLINE_PREFERRED : conversationMode;
            this.currentSourcesRequested = currentSourcesRequested;
            this.approximateArea = approximateArea;
            this.conversationCursor = clean(conversationCursor);
        }
    }

    public static final class TurnResult {
        public final String requestId;
        public final String personScopeId;
        public final long completedAtEpochMs;
        public final String spoken;
        public final String privateMind;
        public final String factualTruth;
        public final Classification classification;
        public final TurnRoute route;
        public final SourceReceipt sourceReceipt;
        public final OperationError error;

        public TurnResult(
                String requestId,
                String personScopeId,
                long completedAtEpochMs,
                String spoken,
                String privateMind,
                String factualTruth,
                Classification classification,
                TurnRoute route,
                SourceReceipt sourceReceipt,
                OperationError error) {
            this.requestId = required(requestId, "Request ID");
            this.personScopeId = required(personScopeId, "Person scope ID");
            this.completedAtEpochMs = completedAtEpochMs;
            this.spoken = required(spoken, "Spoken reply");
            this.privateMind = clean(privateMind);
            this.factualTruth = required(factualTruth, "Factual truth");
            this.classification = classification == null
                    ? Classification.UNCERTAIN_BELIEF : classification;
            this.route = route == null ? TurnRoute.TOOL_UNAVAILABLE : route;
            this.sourceReceipt = sourceReceipt == null
                    ? SourceReceipt.none("No source receipt supplied.", completedAtEpochMs)
                    : sourceReceipt;
            this.error = error == null ? OperationError.none() : error;
            if (this.route == TurnRoute.PUBLIC_SOURCE_TOOL_RESULT
                    && !this.sourceReceipt.applied) {
                throw new IllegalArgumentException(
                        "PUBLIC_SOURCE_TOOL_RESULT requires an applied source receipt");
            }
        }
    }

    public static final class CurrentAreaRequest {
        public final String requestId;
        public final String personScopeId;
        public final boolean ownerPermissionGranted;

        public CurrentAreaRequest(
                String requestId, String personScopeId, boolean ownerPermissionGranted) {
            this.requestId = required(requestId, "Request ID");
            this.personScopeId = required(personScopeId, "Person scope ID");
            this.ownerPermissionGranted = ownerPermissionGranted;
        }
    }

    public static final class CurrentSourceRequest {
        public final String requestId;
        public final String personScopeId;
        public final String query;
        public final ApproximateArea approximateArea;

        public CurrentSourceRequest(
                String requestId,
                String personScopeId,
                String query,
                ApproximateArea approximateArea) {
            this.requestId = required(requestId, "Request ID");
            this.personScopeId = required(personScopeId, "Person scope ID");
            this.query = required(query, "Current-source query");
            this.approximateArea = approximateArea;
        }
    }

    public static final class CurrentSourceResult {
        public final SourceReceipt receipt;
        public final List<String> boundedSummaries;
        public final OperationError error;

        public CurrentSourceResult(
                SourceReceipt receipt,
                List<String> boundedSummaries,
                OperationError error) {
            this.receipt = receipt == null
                    ? SourceReceipt.none("No current source supplied.", 0L) : receipt;
            this.boundedSummaries = immutableStrings(boundedSummaries);
            this.error = error == null ? OperationError.none() : error;
        }
    }

    public static final class VoiceRequest {
        public final String requestId;
        public final String personScopeId;
        public final String spokenText;
        public final String approvedVoiceProfileId;

        public VoiceRequest(
                String requestId,
                String personScopeId,
                String spokenText,
                String approvedVoiceProfileId) {
            this.requestId = required(requestId, "Request ID");
            this.personScopeId = required(personScopeId, "Person scope ID");
            this.spokenText = required(spokenText, "Spoken text");
            this.approvedVoiceProfileId = required(
                    approvedVoiceProfileId, "Approved voice profile ID");
        }
    }

    public static final class VoiceReceipt {
        public final String requestId;
        public final VoiceRoute actualRoute;
        public final boolean completed;
        public final long startedAtEpochMs;
        public final long completedAtEpochMs;
        public final OperationError error;

        public VoiceReceipt(
                String requestId,
                VoiceRoute actualRoute,
                boolean completed,
                long startedAtEpochMs,
                long completedAtEpochMs,
                OperationError error) {
            this.requestId = required(requestId, "Request ID");
            this.actualRoute = actualRoute == null ? VoiceRoute.NONE : actualRoute;
            this.completed = completed;
            this.startedAtEpochMs = startedAtEpochMs;
            this.completedAtEpochMs = completedAtEpochMs;
            this.error = error == null ? OperationError.none() : error;
        }
    }

    public static final class AccessSnapshot {
        public final AccessState state;
        public final boolean renewable;
        public final long expiresAtEpochMs;
        public final String factualTruth;

        public AccessSnapshot(
                AccessState state,
                boolean renewable,
                long expiresAtEpochMs,
                String factualTruth) {
            this.state = state == null ? AccessState.UNAVAILABLE : state;
            this.renewable = renewable;
            this.expiresAtEpochMs = expiresAtEpochMs;
            this.factualTruth = required(factualTruth, "Access factual truth");
        }
    }

    public static final class ProfileSummary {
        public final String personScopeId;
        public final String displayName;
        public final boolean memoryConsent;

        public ProfileSummary(String personScopeId, String displayName, boolean memoryConsent) {
            this.personScopeId = required(personScopeId, "Person scope ID");
            this.displayName = required(displayName, "Display name");
            this.memoryConsent = memoryConsent;
        }
    }

    public static final class DomainRecord {
        public final String recordId;
        public final String personScopeId;
        public final String kind;
        public final String displayLabel;
        public final String status;
        public final long startsAtEpochMs;

        public DomainRecord(
                String recordId,
                String personScopeId,
                String kind,
                String displayLabel,
                String status,
                long startsAtEpochMs) {
            this.recordId = required(recordId, "Record ID");
            this.personScopeId = required(personScopeId, "Person scope ID");
            this.kind = required(kind, "Record kind");
            this.displayLabel = required(displayLabel, "Display label");
            this.status = clean(status);
            this.startsAtEpochMs = startsAtEpochMs;
        }
    }

    public static final class WorkbenchDestination {
        public final String destinationId;
        public final String displayLabel;
        public final boolean available;

        public WorkbenchDestination(
                String destinationId, String displayLabel, boolean available) {
            this.destinationId = required(destinationId, "Destination ID");
            this.displayLabel = required(displayLabel, "Display label");
            this.available = available;
        }
    }

    static String required(String value, String label) {
        String cleaned = clean(value);
        if (cleaned.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return cleaned;
    }

    static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    static List<String> immutableStrings(List<String> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        List<String> copy = new ArrayList<>();
        for (String value : values) {
            String clean = clean(value);
            if (!clean.isEmpty()) copy.add(clean);
        }
        return Collections.unmodifiableList(copy);
    }
}
