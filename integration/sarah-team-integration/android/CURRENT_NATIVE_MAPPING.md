# Current native Android mapping

`CurrentSarahAndroidAdapter.NativePorts` is intentionally a seam rather than a
copy of the current app. Bind it inside the Android application module so the
existing stores, lifecycle rules, and cancellation behavior remain
authoritative.

| Integration port | Current native source | Binding rule |
|---|---|---|
| conversation | `MainActivity`, `ConnectedModelGateway`, `ConnectedModelResponse`, `SarahChannelResponse`, `TurnRoute` | Reuse the bounded retry and channel parser. Return the exact actual route. Never infer online state from prose. |
| approximate area | `ApproximateLocationCoordinator`, `SarahLocationStore`, `CurrentLocationPolicy` | Return only a short-lived profile-scoped area label. Preserve permission denial and manual-entry source. |
| current sources | `ConnectedModelGateway`, `ProtectedBackendCapabilities`, current-source receipt logic in `MainActivity` | Require an applied source receipt before returning `PUBLIC_SOURCE_TOOL_RESULT`. |
| renewable access | host-owned durable enrollment/session layer plus `ProtectedBackendCapabilities` | Expose state and renewal completion only. Do not expose an endpoint or reusable credential through the integration API. |
| protected voice | `SarahVoiceRouter`, `CloudVoiceClient`, `VoiceReceiptStore` | Submit only public spoken text and the approved voice-profile ID. Return actual route, cancellation, and timing truth. |
| profiles | `PersonProfileStore`, `ProfileCorrectionStore`, `OwnerProfileDataMigrator` | Keep exact person IDs, confirmation, and correction history. Never merge by display name. |
| trips | `TripPlanStore`, `EventTripStore`, `RoadTripProfileStore` | Preserve profile scope, status, and source provenance. Listing or opening is not a booking. |
| calendar | `TravelCalendarActivity`, `GmailTokenVault`, `EmailCalendarPolicy` | Expose owner-approved calendar items, not mailbox credentials or raw email access. |
| wallet | `LoyaltyVaultStore`, `TicketPassVaultStore` and their activities | Return bounded display records. Passwords, payment secrets, and decrypted ticket images do not cross this interface. |
| Workbench | `TravelHubActivity` and the existing destination activities | Map destination IDs to navigation. Opening a screen does not assert a search or external action happened. |

## Suggested binding location

Create one app-module class implementing `NativePorts`; inject Android context
or lifecycle owners there. Do not place an `Activity`, database handle, or
credential inside the neutral package. The adapter may be reused by a new
Compose view, the current Views UI, or a non-Android shell.

## Current source and fallback sequence

1. Detect whether the exact request asks for current sources or current area.
2. Resolve/confirm an approximate area only when needed.
3. Ask the host access boundary to renew if its state requires renewal.
4. Run the existing connected route.
5. Attach the exact source receipt to the result.
6. On connected failure, use the current offline route only if it returns
   `ONLINE_FAILED_FELL_BACK_OFFLINE` and factual truth says what failed.
7. Deliver text immediately; start the cancellable voice handoff separately.
