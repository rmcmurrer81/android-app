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

/** Host-owned services. Implementations retain transport and auth internally. */
public final class HostServices {
    private HostServices() { }

    public interface RenewableAccessBoundary {
        AccessSnapshot snapshot();
        Cancellable renew(Completion<AccessSnapshot> completion);
    }

    public interface ConversationGateway {
        Cancellable send(TurnRequest request, Completion<TurnResult> completion);
    }

    public interface ApproximateAreaGateway {
        Cancellable resolve(
                CurrentAreaRequest request, Completion<ApproximateArea> completion);
    }

    public interface CurrentSourceGateway {
        Cancellable search(
                CurrentSourceRequest request, Completion<CurrentSourceResult> completion);
    }

    public interface ProtectedVoiceGateway {
        Cancellable synthesize(VoiceRequest request, Completion<VoiceReceipt> completion);
    }

    public interface ProfileGateway {
        Cancellable listProfiles(
                String requestId, Completion<List<ProfileSummary>> completion);
        Cancellable selectProfile(
                String requestId,
                String personScopeId,
                Completion<ProfileSummary> completion);
    }

    public interface DomainStoreGateway {
        Cancellable list(
                String requestId,
                String personScopeId,
                Completion<List<DomainRecord>> completion);
        Cancellable upsert(
                String requestId,
                DomainRecord record,
                Completion<DomainRecord> completion);
    }

    public interface WorkbenchGateway {
        Cancellable listDestinations(
                String requestId,
                String personScopeId,
                Completion<List<WorkbenchDestination>> completion);
        Cancellable openDestination(
                String requestId,
                String personScopeId,
                String destinationId,
                Completion<WorkbenchDestination> completion);
    }
}
