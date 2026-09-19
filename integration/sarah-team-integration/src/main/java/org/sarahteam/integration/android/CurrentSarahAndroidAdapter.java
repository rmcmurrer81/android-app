package org.sarahteam.integration.android;

import java.util.List;

import org.sarahteam.integration.api.HostServices;
import org.sarahteam.integration.api.SarahIntegration;
import org.sarahteam.integration.api.SarahTypes.AccessSnapshot;
import org.sarahteam.integration.api.SarahTypes.ApproximateArea;
import org.sarahteam.integration.api.SarahTypes.Cancellable;
import org.sarahteam.integration.api.SarahTypes.Completion;
import org.sarahteam.integration.api.SarahTypes.CurrentAreaRequest;
import org.sarahteam.integration.api.SarahTypes.CurrentSourceRequest;
import org.sarahteam.integration.api.SarahTypes.CurrentSourceResult;
import org.sarahteam.integration.api.SarahTypes.DomainRecord;
import org.sarahteam.integration.api.SarahTypes.ErrorCode;
import org.sarahteam.integration.api.SarahTypes.OperationError;
import org.sarahteam.integration.api.SarahTypes.ProfileSummary;
import org.sarahteam.integration.api.SarahTypes.TurnRequest;
import org.sarahteam.integration.api.SarahTypes.TurnResult;
import org.sarahteam.integration.api.SarahTypes.VoiceReceipt;
import org.sarahteam.integration.api.SarahTypes.VoiceRequest;
import org.sarahteam.integration.api.SarahTypes.WorkbenchDestination;

/**
 * Adapter target for the current native Android implementation.
 *
 * The app supplies NativePorts from its existing stores and route classes.
 * This module deliberately imports no Activity, database, network, or provider
 * class, so another UI can use the same contract without inheriting Android.
 */
public final class CurrentSarahAndroidAdapter implements SarahIntegration {
    public interface NativePorts {
        HostServices.RenewableAccessBoundary access();
        HostServices.ConversationGateway conversation();
        HostServices.ApproximateAreaGateway approximateArea();
        HostServices.CurrentSourceGateway currentSources();
        HostServices.ProtectedVoiceGateway protectedVoice();
        HostServices.ProfileGateway profiles();
        HostServices.DomainStoreGateway trips();
        HostServices.DomainStoreGateway calendar();
        HostServices.DomainStoreGateway wallet();
        HostServices.WorkbenchGateway workbench();
    }

    private final NativePorts ports;

    public CurrentSarahAndroidAdapter(NativePorts ports) {
        if (ports == null) throw new IllegalArgumentException("Native ports are required");
        this.ports = ports;
    }

    @Override public Cancellable sendTurn(
            TurnRequest request, Completion<TurnResult> completion) {
        if (request == null) return fail(
                completion, ErrorCode.INVALID_REQUEST, "A turn request is required.");
        return required(ports.conversation(), "Conversation port").send(request, completion);
    }

    @Override public Cancellable resolveApproximateArea(
            CurrentAreaRequest request, Completion<ApproximateArea> completion) {
        if (request == null) return fail(
                completion, ErrorCode.INVALID_REQUEST, "A location request is required.");
        if (!request.ownerPermissionGranted) return fail(
                completion,
                ErrorCode.LOCATION_DENIED,
                "Approximate location permission was not granted; the host must ask for an owner-entered area instead of guessing.");
        return required(ports.approximateArea(), "Approximate-area port")
                .resolve(request, completion);
    }

    @Override public Cancellable searchCurrentSources(
            CurrentSourceRequest request, Completion<CurrentSourceResult> completion) {
        if (request == null) return fail(
                completion, ErrorCode.INVALID_REQUEST, "A current-source request is required.");
        return required(ports.currentSources(), "Current-source port")
                .search(request, completion);
    }

    @Override public Cancellable synthesizeVoice(
            VoiceRequest request, Completion<VoiceReceipt> completion) {
        if (request == null) return fail(
                completion, ErrorCode.INVALID_REQUEST, "A voice request is required.");
        return required(ports.protectedVoice(), "Protected-voice port")
                .synthesize(request, completion);
    }

    @Override public AccessSnapshot accessStatus() {
        return required(ports.access(), "Access boundary").snapshot();
    }

    @Override public Cancellable renewAccess(Completion<AccessSnapshot> completion) {
        return required(ports.access(), "Access boundary").renew(completion);
    }

    @Override public Cancellable listProfiles(
            String requestId, Completion<List<ProfileSummary>> completion) {
        return required(ports.profiles(), "Profile port")
                .listProfiles(require(requestId, "Request ID"), completion);
    }

    @Override public Cancellable selectProfile(
            String requestId,
            String personScopeId,
            Completion<ProfileSummary> completion) {
        return required(ports.profiles(), "Profile port").selectProfile(
                require(requestId, "Request ID"),
                require(personScopeId, "Person scope ID"),
                completion);
    }

    @Override public Cancellable listTrips(
            String requestId,
            String personScopeId,
            Completion<List<DomainRecord>> completion) {
        return listDomain(ports.trips(), requestId, personScopeId, completion);
    }

    @Override public Cancellable upsertTrip(
            String requestId,
            DomainRecord trip,
            Completion<DomainRecord> completion) {
        return upsertDomain(ports.trips(), requestId, trip, completion);
    }

    @Override public Cancellable listCalendar(
            String requestId,
            String personScopeId,
            Completion<List<DomainRecord>> completion) {
        return listDomain(ports.calendar(), requestId, personScopeId, completion);
    }

    @Override public Cancellable upsertCalendar(
            String requestId,
            DomainRecord item,
            Completion<DomainRecord> completion) {
        return upsertDomain(ports.calendar(), requestId, item, completion);
    }

    @Override public Cancellable listWallet(
            String requestId,
            String personScopeId,
            Completion<List<DomainRecord>> completion) {
        return listDomain(ports.wallet(), requestId, personScopeId, completion);
    }

    @Override public Cancellable upsertWallet(
            String requestId,
            DomainRecord item,
            Completion<DomainRecord> completion) {
        return upsertDomain(ports.wallet(), requestId, item, completion);
    }

    @Override public Cancellable listWorkbenchDestinations(
            String requestId,
            String personScopeId,
            Completion<List<WorkbenchDestination>> completion) {
        return required(ports.workbench(), "Workbench port").listDestinations(
                require(requestId, "Request ID"),
                require(personScopeId, "Person scope ID"),
                completion);
    }

    @Override public Cancellable openWorkbenchDestination(
            String requestId,
            String personScopeId,
            String destinationId,
            Completion<WorkbenchDestination> completion) {
        return required(ports.workbench(), "Workbench port").openDestination(
                require(requestId, "Request ID"),
                require(personScopeId, "Person scope ID"),
                require(destinationId, "Workbench destination ID"),
                completion);
    }

    private static Cancellable listDomain(
            HostServices.DomainStoreGateway gateway,
            String requestId,
            String personScopeId,
            Completion<List<DomainRecord>> completion) {
        return required(gateway, "Domain store port").list(
                require(requestId, "Request ID"),
                require(personScopeId, "Person scope ID"),
                completion);
    }

    private static Cancellable upsertDomain(
            HostServices.DomainStoreGateway gateway,
            String requestId,
            DomainRecord record,
            Completion<DomainRecord> completion) {
        if (record == null) return fail(
                completion, ErrorCode.INVALID_REQUEST, "A domain record is required.");
        return required(gateway, "Domain store port").upsert(
                require(requestId, "Request ID"), record, completion);
    }

    private static <T> Cancellable fail(
            Completion<T> completion, ErrorCode code, String truth) {
        if (completion != null) {
            completion.failed(new OperationError(code, false, truth));
        }
        return org.sarahteam.integration.api.SarahTypes.NO_OP_CANCELLABLE;
    }

    private static String require(String value, String label) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return clean;
    }

    private static <T> T required(T value, String label) {
        if (value == null) throw new IllegalStateException(label + " is unavailable");
        return value;
    }
}
