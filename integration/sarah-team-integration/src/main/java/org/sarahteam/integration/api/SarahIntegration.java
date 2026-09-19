package org.sarahteam.integration.api;

import java.util.List;

import org.sarahteam.integration.api.SarahTypes.AccessSnapshot;
import org.sarahteam.integration.api.SarahTypes.ApproximateArea;
import org.sarahteam.integration.api.SarahTypes.Cancellable;
import org.sarahteam.integration.api.SarahTypes.Completion;
import org.sarahteam.integration.api.SarahTypes.CurrentAreaRequest;
import org.sarahteam.integration.api.SarahTypes.CurrentSourceRequest;
import org.sarahteam.integration.api.SarahTypes.CurrentSourceResult;
import org.sarahteam.integration.api.SarahTypes.DomainRecord;
import org.sarahteam.integration.api.SarahTypes.ProfileSummary;
import org.sarahteam.integration.api.SarahTypes.TurnRequest;
import org.sarahteam.integration.api.SarahTypes.TurnResult;
import org.sarahteam.integration.api.SarahTypes.VoiceReceipt;
import org.sarahteam.integration.api.SarahTypes.VoiceRequest;
import org.sarahteam.integration.api.SarahTypes.WorkbenchDestination;

/** UI-neutral Sarah boundary exposed to a host application. */
public interface SarahIntegration {
    String SCHEMA_VERSION = "sarah-team-integration-v1";

    Cancellable sendTurn(TurnRequest request, Completion<TurnResult> completion);

    Cancellable resolveApproximateArea(
            CurrentAreaRequest request, Completion<ApproximateArea> completion);

    Cancellable searchCurrentSources(
            CurrentSourceRequest request, Completion<CurrentSourceResult> completion);

    Cancellable synthesizeVoice(
            VoiceRequest request, Completion<VoiceReceipt> completion);

    AccessSnapshot accessStatus();

    Cancellable renewAccess(Completion<AccessSnapshot> completion);

    Cancellable listProfiles(
            String requestId, Completion<List<ProfileSummary>> completion);

    Cancellable selectProfile(
            String requestId,
            String personScopeId,
            Completion<ProfileSummary> completion);

    Cancellable listTrips(
            String requestId,
            String personScopeId,
            Completion<List<DomainRecord>> completion);

    Cancellable upsertTrip(
            String requestId,
            DomainRecord trip,
            Completion<DomainRecord> completion);

    Cancellable listCalendar(
            String requestId,
            String personScopeId,
            Completion<List<DomainRecord>> completion);

    Cancellable upsertCalendar(
            String requestId,
            DomainRecord item,
            Completion<DomainRecord> completion);

    Cancellable listWallet(
            String requestId,
            String personScopeId,
            Completion<List<DomainRecord>> completion);

    Cancellable upsertWallet(
            String requestId,
            DomainRecord item,
            Completion<DomainRecord> completion);

    Cancellable listWorkbenchDestinations(
            String requestId,
            String personScopeId,
            Completion<List<WorkbenchDestination>> completion);

    Cancellable openWorkbenchDestination(
            String requestId,
            String personScopeId,
            String destinationId,
            Completion<WorkbenchDestination> completion);
}
