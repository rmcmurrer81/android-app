package org.sarahteam.integration.examples;

import org.sarahteam.integration.android.CurrentSarahAndroidAdapter;
import org.sarahteam.integration.api.SarahIntegration;
import org.sarahteam.integration.api.SarahTypes.ConversationMode;
import org.sarahteam.integration.api.SarahTypes.TurnRequest;

/** Secret-free example used by a native Android host during wiring. */
public final class NativeAndroidExample {
    private NativeAndroidExample() { }

    public static SarahIntegration connect(
            CurrentSarahAndroidAdapter.NativePorts currentNativePorts) {
        return new CurrentSarahAndroidAdapter(currentNativePorts);
    }

    public static TurnRequest exampleNearbyTurn(long nowEpochMs) {
        return new TurnRequest(
                "example-turn-001",
                "example-person-001",
                nowEpochMs,
                "What is happening near my location this weekend?",
                ConversationMode.ONLINE_PREFERRED,
                true,
                null,
                "");
    }
}
