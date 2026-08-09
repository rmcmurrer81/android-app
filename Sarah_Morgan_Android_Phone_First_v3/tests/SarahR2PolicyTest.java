package com.kiraworld.sarahtravel;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

public final class SarahR2PolicyTest {
    public static void main(String[] args) {
        Map<String, String> placeholder = new HashMap<>();
        placeholder.put("name", "Phone owner");
        placeholder.put("age", "18");
        require(!ProfileMigrationPolicy.ownerAgeKnown(placeholder), "placeholder age 18 must remain unknown");
        require(ProfileMigrationPolicy.shouldMergePlaceholder("Phone Owner", "Robert"), "Robert migration");
        require(ProfileMigrationPolicy.shouldMergePlaceholder("Phone Owner", "Taylor"), "fresh non-Robert migration");
        require(!ProfileMigrationPolicy.shouldMergePlaceholder("Robert", "Robert"), "do not merge confirmed profile");
        String needsCollision = ProfileMigrationPolicy.collisionRecordId(
                "traveler_needs", "phone-owner", "robert", "old payload", "new payload");
        require(needsCollision.length() == 64, "collision record uses SHA-256");
        require(needsCollision.equals(ProfileMigrationPolicy.collisionRecordId(
                        "traveler_needs", "phone-owner", "robert", "old payload", "new payload")),
                "collision record is deterministic and crash resumable");
        require(!needsCollision.equals(ProfileMigrationPolicy.collisionRecordId(
                        "traveler_needs", "phone-owner", "robert", "different payload", "new payload")),
                "distinct source payload cannot overwrite one archive record");
        String migratedId = ProfileMigrationPolicy.migratedRecordId(
                "loyalty", "program|kind|member|tier|website|notes");
        require(migratedId.startsWith("migrated-") && migratedId.length() == 29,
                "loyalty collision ID is deterministic and bounded");
        require(migratedId.equals(ProfileMigrationPolicy.migratedRecordId(
                        "loyalty", "program|kind|member|tier|website|notes")),
                "loyalty collision retry reuses the same ID");

        Map<String, String> robert = new HashMap<>();
        robert.put("name", "Robert");
        robert.put("age", "45");
        robert.put("age_known", "yes");
        require(ProfileMigrationPolicy.ownerAge(robert) == 45, "preserve confirmed age");
        robert.put("age", "999");
        require(!ProfileMigrationPolicy.ownerAgeKnown(robert), "reject out-of-range known age");
        robert.put("age", "0");
        require(!ProfileMigrationPolicy.ownerAgeKnown(robert), "reject zero known age");

        require(TurnRoute.sourceLabel(TurnRoute.ONLINE_WORKERS_AI).equals("Online mind"), "online label");
        require(TurnRoute.sourceLabel(TurnRoute.OFFLINE_LOCAL).contains("saved knowledge"), "offline label");
        require(TurnRoute.sourceLabel(TurnRoute.LOCAL_TOOL_RESULT).contains("On-device"), "local tool label");
        require(!TurnRoute.sourceLabel(TurnRoute.LOCAL_TOOL_RESULT).contains("verified"), "local tool does not claim current verification");
        require(TurnRoute.sourceLabel(TurnRoute.PUBLIC_SOURCE_TOOL_RESULT).contains("public-source"), "public tool label");
        require(TurnRoute.runtimeFact(TurnRoute.ONLINE_FAILED_FELL_BACK_OFFLINE).contains("failed"), "fallback fact");
        require(!TurnRoute.runtimeFact(TurnRoute.OFFLINE_LOCAL).contains("may truthfully say the online"), "offline cannot claim online");
        require(TurnRoute.connectedRoute("openai").equals(TurnRoute.ONLINE_OPENAI), "OpenAI route truth");
        require(TurnRoute.connectedRoute("workers-ai").equals(TurnRoute.ONLINE_WORKERS_AI), "Workers route truth");

        require(CurrentLocationPolicy.asksForCurrentArea("What can I do near me this weekend?"), "near-me intent");
        require(!CurrentLocationPolicy.asksForCurrentArea("Tell me about Brazil"), "destination is not current location");
        require(CurrentLocationPolicy.fresh(1_000L, 1_000L + CurrentLocationPolicy.MAX_AGE_MS), "boundary fresh");
        require(!CurrentLocationPolicy.fresh(1_000L, 1_001L + CurrentLocationPolicy.MAX_AGE_MS), "stale location");
        require(!CurrentLocationPolicy.profileKey("Robert/1").equals(CurrentLocationPolicy.profileKey("Taylor/2")), "profile isolation");
        require(CurrentLocationPolicy.settingsStatus(
                        "Newark, New Jersey", CurrentLocationPolicy.SOURCE_DEVICE_RESOLVED)
                        .startsWith("Device-resolved approximate current area"),
                "device-resolved area is labelled as device-resolved");
        require(CurrentLocationPolicy.settingsStatus(
                        "Newark, New Jersey", CurrentLocationPolicy.SOURCE_MANUAL)
                        .contains("entered by you"),
                "manual area is never presented as a device location fix");
        require(BookingImportTextPolicy.accepted("https://example.test/booking/123"),
                "bounded owner-reviewed booking text is accepted");
        require(!BookingImportTextPolicy.accepted(
                        "x".repeat(BookingImportTextPolicy.MAX_CHARS + 1)),
                "externally supplied booking text has an exact character limit");
        require(!BookingImportTextPolicy.accepted("€".repeat(11_000)),
                "UTF-8 byte limit is enforced independently of Java character count");

        require(BackgroundResearchPolicy.canRun(true, true, true, true, true, "New Zealand", "Power Rangers"), "bounded destination-interest queue");
        require(!BackgroundResearchPolicy.canRun(true, false, true, true, true, "New Zealand", "Power Rangers"), "no false backend work");
        require(BackgroundResearchPolicy.MAX_PACKS_PER_RUN == 2, "bounded pack count");
        require(BackgroundResearchPolicy.leaseStillValid(
                        "owner-1", "owner-1", true, true, true, false),
                "active exact-profile research lease begins valid");
        require(!BackgroundResearchPolicy.leaseStillValid(
                        "owner-1", "owner-1", false, true, true, false),
                "owner opt-out during a request invalidates the lease before save or notification");
        require(!BackgroundResearchPolicy.leaseStillValid(
                        "owner-1", "guest-2", true, true, true, false),
                "profile switch during a request prevents cross-profile save or notification");
        List<AdaptiveResearchPlan.Query> adaptive = AdaptiveResearchPlan.build(
                "New Zealand", "Power Rangers", "Newark, New Jersey", true);
        require(adaptive.size() == 2, "destination and nearby research remain bounded");
        require(adaptive.get(0).text.contains("Power Rangers")
                        && adaptive.get(0).text.contains("New Zealand"),
                "saved interest and planned destination shape the first query");
        require(adaptive.get(1).text.contains("Newark, New Jersey")
                        && adaptive.get(1).category.equals("nearby_interest"),
                "approved nearby area shapes the separate second query");
        require(AdaptiveResearchPlan.build("", "", "", false).isEmpty(),
                "fresh profile does not inherit another person's research context");

        List<MemoryExtractor.Candidate> learnedCandidates = MemoryExtractor.extract(
                "I like Power Rangers");
        String learnedInterests = learnedCandidates.stream()
                .filter(candidate -> "interest".equals(candidate.category))
                .map(candidate -> candidate.summary)
                .findFirst()
                .orElse("");
        require(learnedInterests.contains("Power Rangers"),
                "natural interest statement produces a durable interest candidate");
        Map<String, String> naturalProfile = new HashMap<>();
        naturalProfile.put("name", "Robert");
        naturalProfile.put("person_id", "owner-robert");
        naturalProfile.put("active_speaker_is_owner", "yes");
        naturalProfile.put("memory_consent", "yes");
        naturalProfile.put("interests", "");
        naturalProfile.put("learned_interests", learnedInterests);
        require(ProfileLearningContext.interests(naturalProfile).contains("Power Rangers"),
                "empty onboarding interests cannot mask learned exact-profile interests");
        AgenticTravelPlanner.Plan naturalPlan = AgenticTravelPlanner.plan(
                "I'm planning a trip to New Zealand",
                naturalProfile,
                List.of(Map.of("role", "user", "content", "I like Power Rangers")),
                List.of(Map.of("category", "interest", "summary", learnedInterests)));
        require(naturalPlan.actions.stream().anyMatch(action ->
                        AgenticTravelPlanner.SAVE_PLANNED_TRIP.equals(action.type)
                                && "New Zealand".equals(action.destination)),
                "explicit natural planned-trip wording creates a profile-owned planned trip action");
        require(naturalPlan.actions.stream().anyMatch(action ->
                        AgenticTravelPlanner.QUEUE_KNOWLEDGE_PACK.equals(action.type)
                                && "New Zealand".equals(action.destination)),
                "explicit natural planned trip creates a separate bounded research request");
        List<Map<String, String>> naturalTrips = List.of(
                Map.of("destination", "New Zealand", "status", "planned"));
        List<AdaptiveResearchPlan.Query> naturalResearch = AdaptiveResearchPlan.build(
                naturalTrips.get(0).get("destination"),
                ProfileLearningContext.interests(naturalProfile),
                "",
                false);
        require(naturalResearch.stream().anyMatch(query ->
                        query.text.contains("Power Rangers") && query.text.contains("New Zealand")),
                "natural saved interest and planned destination feed adaptive research without test seeding");
        require(CalmSupport.questions(naturalProfile, naturalTrips, List.of()).stream().anyMatch(question ->
                        question.prompt.contains("saved Power Rangers travel game")),
                "natural saved interest and planned destination feed offline calming trivia");
        Map<String, String> isolatedProfile = new HashMap<>();
        isolatedProfile.put("name", "Taylor");
        isolatedProfile.put("person_id", "guest-taylor");
        isolatedProfile.put("memory_consent", "yes");
        require(ProfileLearningContext.interests(isolatedProfile).isEmpty(),
                "another exact profile does not inherit Robert's learned interest");
        require(AdaptiveResearchPlan.build(
                "New Zealand", ProfileLearningContext.interests(isolatedProfile), "", false)
                .stream().noneMatch(query -> query.text.contains("Power Rangers")),
                "another exact profile cannot leak Robert's adaptive research interest");

        require(KnowledgePackSchedulingPolicy.canSchedule(
                        true, true, true, true, true, true),
                "all owner, consent, route, source, and opt-in gates permit scheduling");
        require(!KnowledgePackSchedulingPolicy.canSchedule(
                        true, true, true, true, true, false),
                "owner opt-in is mandatory before scheduling");
        require(!KnowledgePackSchedulingPolicy.canSchedule(
                        true, true, false, true, true, true),
                "validated internet is mandatory before scheduling");
        require(!KnowledgePackSchedulingPolicy.canSchedule(
                        false, true, true, true, true, true),
                "non-owner profile cannot schedule owner background research");
        require(KnowledgePackSchedulingPolicy.settingsCanEnable(
                        true, true, true, true, true, true, false),
                "Settings offers research only when every runnable prerequisite is present");
        require(!KnowledgePackSchedulingPolicy.settingsCanEnable(
                        true, true, true, true, false, true, false),
                "Settings disables research without the protected source route");
        require(!KnowledgePackSchedulingPolicy.settingsCanEnable(
                        true, false, true, true, true, true, false),
                "Settings disables research without memory permission");
        require(!KnowledgePackSchedulingPolicy.settingsCanEnable(
                        true, true, true, true, true, false, false),
                "Settings disables research when public research is off");
        require(!KnowledgePackSchedulingPolicy.settingsCanEnable(
                        true, true, true, true, true, true, true),
                "Settings disables research in local-only mode");
        require(!KnowledgePackSchedulingPolicy.persistEnabled(
                        true, true, true, false, true, true, true, false),
                "a stale requested true cannot persist without validated internet");
        require(KnowledgePackSchedulingPolicy.settingsLabel(
                        true, true, true, true, false, true, false)
                        .contains("setup required"),
                "missing protected research route has a human setup-required label");
        require(KnowledgePackSchedulingPolicy.settingsLabel(
                        true, false, true, true, true, true, false)
                        .contains("memory permission required"),
                "missing research consent has a human permission-required label");
        require(!BackgroundResearchPolicy.DEFAULT_BACKGROUND_MONITORING_ENABLED,
                "background travel monitoring defaults off");
        require(!BackgroundResearchPolicy.monitoringCanRun(false, true, true),
                "saved watches cannot imply a background-monitoring opt-in");
        require(!BackgroundResearchPolicy.monitoringCanRun(true, false, true),
                "monitoring cannot run without its configured route");
        require(!BackgroundResearchPolicy.monitoringCanRun(true, true, false),
                "monitoring does not schedule without eligible work");
        require(BackgroundResearchPolicy.monitoringCanRun(true, true, true),
                "explicit opt-in plus route and work permits monitoring");
        require(KnowledgeProfileKey.forProfile(Map.of("is_owner", "yes"))
                        .equals(KnowledgeProfileKey.OWNER),
                "durable owner flag cannot be masked by a missing active-speaker flag");
        require(!KnowledgeProfileKey.forProfile(Map.of(
                        "person_id", "guest-1", "name", "Taylor"))
                        .equals(KnowledgeProfileKey.OWNER),
                "guest knowledge remains in an opaque exact-profile scope");
        require(KnowledgePackSchedulingPolicy.pendingState(false)
                        .equals(KnowledgePackSchedulingPolicy.PENDING_NOT_SCHEDULED),
                "saved request remains explicitly not scheduled until Android accepts a job");
        require(KnowledgePackSchedulingPolicy.pendingState(true)
                        .equals(KnowledgePackSchedulingPolicy.PENDING_SCHEDULED),
                "only scheduler acceptance promotes the request state");
        require(ConnectedTurnPolicy.ATTEMPTS_PER_TURN == 2,
                "connected conversation makes one short retry only after first-attempt failure");
        require(TurnRoute.sourceLabel(TurnRoute.ONLINE_OPENAI).equals("Online mind")
                        && TurnRoute.sourceLabel(TurnRoute.ONLINE_CONNECTED_OTHER).equals("Online mind"),
                "normal owner chat hides provider jargon while exact audit telemetry remains intact");
        require(ConnectedTurnPolicy.maxNetworkWaitMs(false) == 15_000,
                "ordinary conversation retains its strict 15-second maximum");
        require(ConnectedTurnPolicy.maxNetworkWaitMs(true) == 25_000,
                "current-source work receives only its bounded 25-second maximum");
        require(ConnectedTurnPolicy.maxReadTimeoutMs(false) == 11_500,
                "ordinary Gemma conversation retains its useful 11.5-second read window");
        require(ConnectedTurnPolicy.maxReadTimeoutMs(true) == 18_000,
                "source retrieval and source-coupled inference receive at most 18 seconds to read");
        require(ConnectedTurnPolicy.CONNECT_TIMEOUT_MS
                        + ConnectedTurnPolicy.READ_TIMEOUT_MS
                        <= ConnectedTurnPolicy.MAX_NETWORK_WAIT_MS,
                "one useful ordinary attempt fits inside the ordinary network budget");
        require(ConnectedTurnPolicy.CONNECT_TIMEOUT_MS
                        + ConnectedTurnPolicy.SOURCE_READ_TIMEOUT_MS
                        <= ConnectedTurnPolicy.SOURCE_MAX_NETWORK_WAIT_MS,
                "one useful source-backed attempt fits inside the source network budget");
        require(ConnectedTurnPolicy.connectTimeoutMs(15_000L)
                        + ConnectedTurnPolicy.readTimeoutMs(15_000L) <= 15_000,
                "ordinary socket timeouts cannot exceed the owner-visible deadline");
        require(ConnectedTurnPolicy.connectTimeoutMs(25_000L, true)
                        + ConnectedTurnPolicy.readTimeoutMs(25_000L, true) <= 25_000,
                "source socket timeouts cannot exceed the source-backed deadline");
        for (boolean currentSourceRequest : new boolean[] {false, true}) {
            int classCeiling = ConnectedTurnPolicy.maxNetworkWaitMs(currentSourceRequest);
            for (long proposedBudget : new long[] {
                    2L, 1_000L, 4_000L, 15_000L, 25_000L, Long.MAX_VALUE}) {
                int socketBudget = ConnectedTurnPolicy.connectTimeoutMs(
                        proposedBudget, currentSourceRequest)
                        + ConnectedTurnPolicy.readTimeoutMs(
                                proposedBudget, currentSourceRequest);
                require(socketBudget <= classCeiling,
                        "no socket budget may exceed its ordinary/source class ceiling");
            }
        }
        String reboundAttemptUrl = ConnectedTurnPolicy.endpointForAttempt(
                "https://sarah.example.test/?acceptance_probe=production_modelclient"
                        + "&%73arah_attempt=99&sarah_nonce=stale#owner-fragment",
                2,
                "fresh nonce");
        require(reboundAttemptUrl.equals(
                        "https://sarah.example.test/?acceptance_probe=production_modelclient"
                                + "&sarah_attempt=2&sarah_nonce=fresh%20nonce#owner-fragment"),
                "attempt cache-busting preserves the existing query, replaces reserved fields,"
                        + " and keeps the fragment last");
        require(reboundAttemptUrl.indexOf("sarah_attempt=")
                        == reboundAttemptUrl.lastIndexOf("sarah_attempt=")
                        && reboundAttemptUrl.indexOf("sarah_nonce=")
                        == reboundAttemptUrl.lastIndexOf("sarah_nonce="),
                "reserved attempt fields occur exactly once after replacement");
        String generatedAttemptOne = ConnectedTurnPolicy.endpointForAttempt(
                "https://sarah.example.test/?acceptance_probe=production_modelclient#owner", 1);
        String generatedAttemptTwo = ConnectedTurnPolicy.endpointForAttempt(
                "https://sarah.example.test/?acceptance_probe=production_modelclient#owner", 2);
        require(!generatedAttemptOne.equals(generatedAttemptTwo)
                        && generatedAttemptOne.contains("sarah_attempt=1")
                        && generatedAttemptTwo.contains("sarah_attempt=2")
                        && generatedAttemptOne.endsWith("#owner")
                        && generatedAttemptTwo.endsWith("#owner"),
                "successive attempts receive distinct generated attempt and nonce URLs");
        require(ConnectedTurnPolicy.mayRetry(
                        1, 14_000L,
                        new ConnectedTurnPolicy.HttpStatusException(
                                "test backend", 404, "route is still propagating")),
                "a fast transient route-propagation 404 permits the one bounded retry");
        require(ConnectedTurnPolicy.mayRetry(
                        1, 14_000L, new java.net.SocketTimeoutException("transient")),
                "a fast transient transport failure permits the one bounded retry");
        require(!ConnectedTurnPolicy.mayRetry(
                        1, 14_000L,
                        new ConnectedTurnPolicy.HttpStatusException(
                                "test backend", 401, "wrong credential")),
                "an authorization failure never consumes a retry");
        require(!ConnectedTurnPolicy.mayRetry(2, 5_000L),
                "a second failure must fall back locally instead of retrying again");
        require(!ConnectedTurnPolicy.mayRetry(
                        1,
                        ConnectedTurnPolicy.RETRY_BACKOFF_MS
                                + ConnectedTurnPolicy.MIN_SECOND_ATTEMPT_BUDGET_MS - 1L,
                        new java.net.SocketTimeoutException("too late")),
                "a late failure cannot start a second attempt without useful remaining budget");
        require(ConnectedTurnPolicy.remainingBudgetMs(1_000_000L, 1_000_000L)
                        == ConnectedTurnPolicy.MAX_NETWORK_WAIT_MS,
                "ordinary monotonic deadline starts with the strict ordinary budget");
        require(ConnectedTurnPolicy.remainingBudgetMs(
                        1_000_000L, 1_000_000L, true)
                        == ConnectedTurnPolicy.SOURCE_MAX_NETWORK_WAIT_MS,
                "source monotonic deadline starts with the distinct source budget");
        require(ConnectedTurnPolicy.deadlineNanos(1_000_000L, true)
                        - ConnectedTurnPolicy.deadlineNanos(1_000_000L, false)
                        == (ConnectedTurnPolicy.SOURCE_MAX_NETWORK_WAIT_MS
                                - ConnectedTurnPolicy.MAX_NETWORK_WAIT_MS) * 1_000_000L,
                "source and ordinary deadlines are separate exact monotonic ceilings");
        long measuredSlowTurnNs = 19_990_000_000L;
        require(ConnectedTurnPolicy.remainingBudgetMs(
                        1_000_000L, 1_000_000L + measuredSlowTurnNs, true) == 5_010L,
                "the measured 19.990-second source turn remains online inside its source budget");
        require(ConnectedTurnPolicy.remainingBudgetMs(
                        1_000_000L, 1_000_000L + measuredSlowTurnNs, false) == 0L,
                "the same delay cannot leak the source budget into ordinary conversation");
        require(ConnectedTurnPolicy.remainingBudgetMs(
                        1_000_000L, 1_000_000L + 25_001_000_000L, true) == 0L,
                "source-backed work still fails closed after its exact 25-second ceiling");
        long queuedDeadline = ConnectedTurnPolicy.deadlineNanos(1_000_000L);
        long socketBudgetAfterQueueDelay = ConnectedTurnPolicy.remainingUntilDeadlineMs(
                queuedDeadline, 1_000_000L + 4_000_000_000L);
        require(socketBudgetAfterQueueDelay == 11_000L
                        && ConnectedTurnPolicy.connectTimeoutMs(socketBudgetAfterQueueDelay)
                        + ConnectedTurnPolicy.readTimeoutMs(socketBudgetAfterQueueDelay)
                        <= socketBudgetAfterQueueDelay,
                "executor delay is deducted before socket timeouts are configured");
        long futureWaitAfterMoreDelay = ConnectedTurnPolicy.remainingUntilDeadlineMs(
                queuedDeadline, 1_000_000L + 6_000_000_000L);
        require(futureWaitAfterMoreDelay == 9_000L
                        && futureWaitAfterMoreDelay < socketBudgetAfterQueueDelay,
                "Future.get receives a fresh smaller deadline after submission delay");
        require(ConnectedTurnPolicy.remainingBudgetMs(
                        1_000_000L,
                        1_000_000L
                                + ConnectedTurnPolicy.MAX_NETWORK_WAIT_MS * 1_000_000L) == 0L,
                "attempt two cannot run past the hard shared connected-turn deadline");
        require(!VoiceFallbackPolicy.shouldStartAndroidFallback(
                        12_345L, "stream_failed_after_playback"),
                "approved progressive playback failure cannot replay the whole reply generically");
        require(VoiceFallbackPolicy.shouldStartAndroidFallback(
                        0L, "network_failed_before_playback"),
                "pre-playback approved-route failure may use the explicit Android fallback");
        require(!VoiceFallbackPolicy.shouldStartAndroidFallback(
                        0L, "superseded_before_playback"),
                "superseded voice request cannot start an obsolete fallback");
        require(!VoiceFallbackPolicy.shouldStartAndroidFallback(
                        0L, "cancelled_by_lifecycle"),
                "destroyed activity cannot start stale cloud or generic playback");
        byte[] boundedDerivative = new byte[SyncPhotoPolicy.MAX_DERIVATIVE_BYTES];
        require(SyncPhotoPolicy.accepted(boundedDerivative, 0),
                "bounded re-encoded sync derivative is accepted at the exact limit");
        require(!SyncPhotoPolicy.accepted(
                        new byte[SyncPhotoPolicy.MAX_DERIVATIVE_BYTES + 1], 0),
                "oversize sync derivative is rejected before base64 serialization");
        require(!SyncPhotoPolicy.accepted(
                        new byte[1], SyncPhotoPolicy.MAX_TOTAL_BYTES),
                "aggregate sync photo bytes remain bounded");
        require(SyncPhotoPolicy.sha256(new byte[]{1, 2, 3}).length() == 64,
                "sanitized derivative has an exact SHA-256 identity");
        require(TrustedLanEndpointPolicy.authority("192.168.1.25")
                        .equals("192.168.1.25:8769"),
                "RFC1918 trusted-device sync uses the one fixed LAN port");
        require(TrustedLanEndpointPolicy.authority("http://127.0.0.1:8769/")
                        .equals("127.0.0.1:8769"),
                "loopback legacy address normalizes without widening transport access");
        require(TrustedLanEndpointPolicy.authority("169.254.3.4")
                        .equals("169.254.3.4:8769"),
                "link-local trusted-device sync is accepted");
        requireThrows(() -> TrustedLanEndpointPolicy.authority("example.com"),
                "public hostname must fail before cleartext transport");
        requireThrows(() -> TrustedLanEndpointPolicy.authority("192.168.1.25:8080"),
                "non-fixed LAN port must fail before transport");
        require(TurnLifecyclePolicy.canSubmit(false, false),
                "first owner turn may submit");
        require(!TurnLifecyclePolicy.canSubmit(false, true),
                "rapid second send is blocked while the first reply is in flight");
        require(TurnLifecyclePolicy.completionMayApply(false, 7L, 7L),
                "same-generation completion may commit its assistant reply");
        require(TurnLifecyclePolicy.nextTurnCanSeePriorReply(false, true),
                "composer reopens only after the prior assistant reply was committed");
        require(!TurnLifecyclePolicy.completionMayApply(false, 7L, 8L),
                "rotation generation invalidates the old request completion");
        require(!TurnLifecyclePolicy.completionMayApply(true, 7L, 7L),
                "destroyed activity rejects late UI, voice, and database completion work");
        require(TurnLifecyclePolicy.speakerCompletionMayApply(
                        false, 7L, 7L, "person-1", "person-1", "Taylor", "Taylor"),
                "same person and generation may receive its own voice completion");
        require(!TurnLifecyclePolicy.speakerCompletionMayApply(
                        false, 7L, 7L, "person-1", "person-2", "Taylor", "Morgan"),
                "profile switch blocks old audio, fallback, receipt UI, and photo completion");
        require(!TurnLifecyclePolicy.speakerCompletionMayApply(
                        false, 7L, 8L, "person-1", "person-1", "Taylor", "Taylor"),
                "generation advance blocks a late sanitizer or voice callback");

        List<CalmSupport.Question> personalizedCalm = CalmSupport.questions(
                Map.of("name", "Taylor", "age", "31", "memory_consent", "yes", "interests", "Power Rangers"),
                List.of(Map.of("destination", "New Zealand", "status", "planned")),
                List.of());
        require(personalizedCalm.stream().anyMatch(question ->
                        question.prompt.contains("saved Power Rangers travel game")
                                && question.explanation.contains("saved trip context")),
                "offline trivia adapts to this profile's saved interest and trip only");

        ConnectedModelResponse workersNoSearch = new ConnectedModelResponse(
                "ordinary conversation", "workers-ai", "gemma", true,
                true, false, List.of(), 1000L);
        require(!workersNoSearch.hasVerifiedWebReceipt(), "Workers prose is not web evidence");
        require(workersNoSearch.turnRoute().equals(TurnRoute.ONLINE_WORKERS_AI), "exact Workers route");
        require(workersNoSearch.auditFact().contains("web_search_applied=false"), "audit missing search failure");
        ConnectedModelResponse openAiSourced = new ConnectedModelResponse(
                "current fact", "openai", "gpt-test", true,
                true, true, List.of("https://example.test/source"), 2000L);
        require(openAiSourced.hasVerifiedWebReceipt(), "completed OpenAI source receipt");
        require(openAiSourced.hasSourceUrl("https://example.test/source"), "exact item source match");
        require(openAiSourced.ownerSourceDetails().contains("https://example.test/source"),
                "owner source drilldown shows the exact verified URL");
        require(!openAiSourced.hasSourceUrl("https://example.test/other"), "reject unmatched item source");
        String returnedRouteTiming = TextTurnReceipt.build(
                openAiSourced.turnRoute(), openAiSourced.provider, openAiSourced.model, 1900L, 2100L);
        require(returnedRouteTiming.contains("provider=openai"), "use actual returned provider");
        require(returnedRouteTiming.contains("model=gpt-test"), "use actual returned model");
        require(DestinationSourcePolicy.canPersistReadyPack(
                List.of("https://example.test/destination"), 2000L), "HTTPS destination receipt");
        require(!DestinationSourcePolicy.canPersistReadyPack(
                List.of("http://example.test/unsafe"), 2000L), "reject non-HTTPS destination source");
        require(!DestinationSourcePolicy.canPersistReadyPack(List.of(), 2000L), "reject missing destination source");
        require(VoiceRoutePolicy.shouldAttemptPremium(1, true, true, false),
                "protected ElevenLabs backend works without direct key");
        require(!VoiceRoutePolicy.shouldAttemptPremium(1, false, true, false),
                "premium voice requires validated internet");
        require(GmailTravelConnection.canClaimConnected(true, true, true),
                "implemented Gmail connector may claim connected only after exact supervised proof");
        require(!GmailTravelConnection.canClaimConnected(true, true, false),
                "Gmail cannot claim connected before a supervised read-only test");
        require(!GmailTravelConnection.canMonitor(false, true),
                "Gmail monitoring cannot run without a real connection");
        require(GmailTravelConnection.canMonitor(true, true),
                "monitoring requires both a real connection and separate owner opt-in");
        require(!GmailTravelConnection.canMonitor(true, false),
                "monitoring remains off after connection until the owner opts in");
        require(GmailTravelConnection.status().contains("runtime connection required"),
                "Gmail static status must not claim a live account connection");
        require(MaturityAccessPolicy.requiresNonAdultSafeContent(Map.of()),
                "missing maturity defaults to non-adult safe content");
        require(MaturityAccessPolicy.requiresNonAdultSafeContent(
                Map.of("age_group", "unexpected")), "malformed maturity remains safe");
        require(!MaturityAccessPolicy.requiresNonAdultSafeContent(
                Map.of("age_group", "adult")), "only confirmed adult uses adult content lane");
        String offlineTiming = TextTurnReceipt.build(
                "turn-fixture", TurnRoute.OFFLINE_LOCAL,
                "on-device", "DemoSarah", 1000L, 1125L);
        require(offlineTiming.contains("text_latency_ms=125"), "offline timing receipt");
        require(offlineTiming.contains("first_token_at=UNAVAILABLE"), "non-streaming timing truth");
        require(offlineTiming.contains("turn_id=turn-fixture"), "text and voice correlation ID");

        require(MemoryExtractor.extract("I want to visit Brazil").stream()
                        .noneMatch(candidate -> "wish_list".equals(candidate.category)),
                "tentative destination must not become a permanent wish");
        require(MemoryExtractor.extract("I am deciding between Brazil and New Zealand").stream()
                        .noneMatch(candidate -> "wish_list".equals(candidate.category)),
                "comparison context must not become a permanent wish");

        Map<String, String> turingProfile = Map.of("name", "Robert");
        require(OfflineTuringPolicy.answer(
                "What is your name?", turingProfile, TurnRoute.OFFLINE_LOCAL)
                .startsWith("My name is Sarah Morgan"), "exact offline name answer");
        require(OfflineTuringPolicy.answer(
                "Who am I?", turingProfile, TurnRoute.OFFLINE_LOCAL)
                .contains("Robert"), "exact active-person identity answer");
        require(OfflineTuringPolicy.answer(
                "Are you online or offline right now, and what can you do in this mode?",
                turingProfile, TurnRoute.OFFLINE_LOCAL)
                .contains("offline mind"), "exact authoritative offline route answer");
        require(OfflineTuringPolicy.answer(
                "Are you online or offline right now, and what can you do in this mode?",
                turingProfile, TurnRoute.ONLINE_WORKERS_AI)
                .contains("connected online mind"), "exact authoritative online route answer");
        require(OfflineTuringPolicy.answer(
                "Are you online or offline right now, and what can you do in this mode?",
                turingProfile, TurnRoute.ONLINE_FAILED_FELL_BACK_OFFLINE)
                .contains("did not answer"), "exact authoritative fallback route answer");
        require(OfflineTuringPolicy.answer(
                "What should I do in Rio for a week?", turingProfile, TurnRoute.ONLINE_WORKERS_AI)
                .isEmpty(), "ordinary conversation must continue through Sarah's normal mind");

        PublicSourceResult verifiedSource = PublicSourceResult.verified(
                "Verified event details.", "https://example.org/official-event");
        require(verifiedSource.verified, "https source receipt must verify");
        require(TurnRoute.PUBLIC_SOURCE_TOOL_RESULT.equals(verifiedSource.turnRoute()),
                "verified source must use public-source route");
        require(verifiedSource.ownerSourceDetails().contains("https://example.org/official-event"),
                "verified source details must expose exact source");
        PublicSourceResult failedSource = PublicSourceResult.verified(
                "Unverified event details.", "http://example.org/not-accepted");
        require(!failedSource.verified, "non-https source must fail closed");
        require(TurnRoute.TOOL_UNAVAILABLE.equals(failedSource.turnRoute()),
                "unverified source attempt must not claim public-source success");
        require(!failedSource.ownerSourceDetails().contains("http://example.org"),
                "unverified source details must not expose a false receipt");

        Map<String, String> traveler = new HashMap<>();
        traveler.put("hometown", "Newark, New Jersey");
        List<Map<String, String>> brazilHistory = new java.util.ArrayList<>();
        brazilHistory.add(Map.of("role", "user", "content", "I am thinking about visiting Brazil"));
        String retainedBrazil = TravelContextResolver.primaryDestination(
                "Not sure yet what do you recommend for a week trip", brazilHistory);
        require(retainedBrazil.equals("Brazil"), "Brazil context survives the first follow-up");
        require(TravelPlanningConversationPolicy.asksForShortTripRecommendation(
                "Not sure yet what do you recommend for a week trip"), "one-week intent");
        String oneWeek = TravelPlanningConversationPolicy.shortTripReply(retainedBrazil);
        require(oneWeek.contains("week in Brazil"),
                "one-week follow-up keeps the exact destination context");
        require(!oneWeek.contains("I'm with you"), "no generic screenshot fallback");
        FinalDisplayedResponsePolicy.Selection guardedOneWeek = FinalDisplayedResponsePolicy.select(
                "Not sure yet what do you recommend for a week trip",
                retainedBrazil,
                oneWeek,
                "I'm with you. We can stay with this subject.",
                TurnRoute.ONLINE_WORKERS_AI,
                false,
                false);
        require(guardedOneWeek.reply.contains("Brazil")
                        && guardedOneWeek.route.equals(TurnRoute.LOCAL_TOOL_RESULT)
                        && !guardedOneWeek.usedConnectedReply,
                "final displayed-response selection rejects a generic connected reply that drops Brazil");
        brazilHistory.add(Map.of("role", "user", "content", "Not sure yet what do you recommend for a week trip"));
        brazilHistory.add(Map.of("role", "assistant", "content", oneWeek));
        String brazilSearchQuery = TravelSearchQueryPolicy.build(
                "Cheapest destination and any time of the year",
                brazilHistory,
                traveler,
                List.of());
        require(brazilSearchQuery.contains("Brazil"),
                "exact cheapest follow-up search keeps Brazil context");
        String retainedBrazilAgain = TravelContextResolver.primaryDestination(
                "Cheapest destination and any time of the year", brazilHistory);
        require(retainedBrazilAgain.equals("Brazil"), "Brazil context survives the cost follow-up");
        require(TravelPlanningConversationPolicy.asksForCurrentCost(
                "Cheapest destination and any time of the year"), "cheapest is current-source intent");
        String cheapest = TravelPlanningConversationPolicy.currentCostReply(retainedBrazilAgain);
        require(cheapest.contains("Current prices require"),
                "cheapest follow-up fails closed without a live source");
        FinalDisplayedResponsePolicy.Selection missingSearchReceipt = FinalDisplayedResponsePolicy.select(
                "Cheapest destination and any time of the year",
                retainedBrazilAgain,
                cheapest,
                "Brazil is cheapest in an invented month.",
                TurnRoute.ONLINE_WORKERS_AI,
                true,
                false);
        require(missingSearchReceipt.route.equals(TurnRoute.TOOL_UNAVAILABLE)
                        && !missingSearchReceipt.usedConnectedReply
                        && missingSearchReceipt.reply.contains("Brazil"),
                "final display fails closed while preserving Brazil when search receipt is absent");
        FinalDisplayedResponsePolicy.Selection verifiedBrazil = FinalDisplayedResponsePolicy.select(
                "Cheapest destination and any time of the year",
                retainedBrazilAgain,
                cheapest,
                "Verified Brazil comparison from the returned source receipt.",
                TurnRoute.ONLINE_WORKERS_AI,
                true,
                true);
        require(verifiedBrazil.usedConnectedReply
                        && verifiedBrazil.route.equals(TurnRoute.ONLINE_WORKERS_AI)
                        && verifiedBrazil.reply.contains("Brazil"),
                "verified connected Brazil answer survives final displayed-response selection");

        traveler.put("runtime_current_area", "Newark, New Jersey");
        traveler.put("runtime_current_area_source", CurrentLocationPolicy.SOURCE_DEVICE_RESOLVED);
        String nearbyQuery = TravelSearchQueryPolicy.build(
                "Is there anything to do near my current location in the near future?",
                List.of(), traveler, List.of());
        require(nearbyQuery.contains("Device-resolved approximate current area: Newark, New Jersey"),
                "near-me search binds the resolved phone area");
        traveler.put("runtime_current_area_source", CurrentLocationPolicy.SOURCE_MANUAL);
        require(TravelSearchQueryPolicy.build(
                        "What is near me?", List.of(), traveler, List.of())
                        .contains("Owner-entered area for nearby search: Newark, New Jersey"),
                "manual area remains truthfully distinct in the exact search query");

        SarahChannelResponse guarded = ReplyTruthGuard.enforce(
                SarahChannelResponse.spokenOnly(
                        "You're welcome. I'll get to work on that. I'll be back with a summary soon.",
                        "No job exists."));
        require(guarded.spoken.equals("You're welcome."), "strip false background promises");
        require(guarded.factualTruth.contains("no durable job"), "record grounding correction");
        SarahChannelResponse simpleCheck = ReplyTruthGuard.enforce(
                SarahChannelResponse.spokenOnly(
                        "I’ll check current events for you.", "No job exists."));
        require(!simpleCheck.spoken.contains("I’ll check"),
                "strip a simple unsupported future check promise");

        SarahChannelResponse withReceipt = ReplyTruthGuard.enforce(
                SarahChannelResponse.spokenOnly("I'll check that and get back to you.", "A persisted request exists."),
                true,
                "fare watch from Newark to Auckland");
        require(withReceipt.spoken.contains("Saved background work"), "show action receipt");
        require(!withReceipt.factualTruth.contains("no durable job"), "do not deny a real job");
        SarahChannelResponse receiptWithoutPromise = ReplyTruthGuard.enforce(
                SarahChannelResponse.spokenOnly("I found a useful option.", "A runnable request exists."),
                true,
                "destination knowledge request for Brazil");
        require(receiptWithoutPromise.spoken.contains("Saved background work"),
                "always expose durable action receipt");
        SarahChannelResponse pendingOnly = ReplyTruthGuard.enforce(
                SarahChannelResponse.spokenOnly(
                        "I'll research it and get back to you.", "A request row exists."),
                false,
                "",
                "destination knowledge request for Brazil (saved, not scheduled)");
        require(pendingOnly.spoken.contains("Saved request (not scheduled)")
                        && !pendingOnly.spoken.contains("Saved background work"),
                "pending request is never represented as runnable background work");
        require(pendingOnly.factualTruth.contains("PENDING_NOT_SCHEDULED"),
                "pending receipt records the exact non-runnable state");

        System.out.println("SARAH_R2_POLICY_TEST_PASS");
    }

    private static void require(boolean value, String label) {
        if (!value) throw new IllegalStateException(label);
    }

    private static void requireThrows(Runnable action, String label) {
        try { action.run(); }
        catch (RuntimeException expected) { return; }
        throw new IllegalStateException(label);
    }
}
