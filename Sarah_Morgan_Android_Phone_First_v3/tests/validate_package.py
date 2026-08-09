from pathlib import Path
import json, xml.etree.ElementTree as ET, re, subprocess, tempfile, shutil
ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/'android-app'
REPO=ROOT.parent

required=[
    APP/'app/src/main/AndroidManifest.xml',
    APP/'gradle.properties',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/MainActivity.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/OnboardingActivity.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/SarahTts.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/OpenAIClient.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/CloudVoiceClient.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/CalmSupport.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/MediaSuggestionEngine.java',
    REPO/'.github/workflows/build-apk.yml',
    REPO/'.github/workflows/sarah-2-2-ci.yml',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/TurnRoute.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/SarahPairingProtocol.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/SarahPairingTransport.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/SarahDeviceDiscovery.java',
    ROOT/'tests/SarahPairingProtocolTest.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/ProfileMigrationPolicy.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/ProfileMigrationArchiveStore.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/CurrentLocationPolicy.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/ApproximateLocationCoordinator.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/ReplyTruthGuard.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/ConnectedModelResponse.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/DestinationSourcePolicy.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/PublicSourceResult.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/VoiceRoutePolicy.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/OwnerProfileDataMigrator.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/TextTurnReceipt.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/GmailTravelConnection.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/GmailReadOnlyPolicy.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/GmailTokenVault.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/GmailReadOnlyClient.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/GmailAuthorizationActivity.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/GmailMonitorScheduler.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/GmailTravelMonitorWorker.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/TravelPlanningConversationPolicy.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/MaturityAccessPolicy.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/TravelSearchQueryPolicy.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/OfflineTuringPolicy.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/VoiceReceiptStore.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/AdaptiveResearchPlan.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/ProactiveResearchReceiptStore.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/ProfileLearningContext.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/KnowledgeProfileKey.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/KnowledgePackSchedulingPolicy.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/FinalDisplayedResponsePolicy.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/ConnectedTurnPolicy.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/VoiceFallbackPolicy.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/SyncPhotoPolicy.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/BookingImportTextPolicy.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/TrustedLanEndpointPolicy.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/EventTripStore.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/EventTripProfilePolicy.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/EventTripMonitoringPolicy.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/EventTripPreUpgradeBackupGate.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/EventTripPreUpgradeVersionPolicy.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/ProtectedBackendCapabilities.java',
    APP/'app/src/main/java/com/kiraworld/sarahtravel/ProtectedBackendCapabilityPolicy.java',
    ROOT/'tests/EventTripProfilePolicyTest.java',
    ROOT/'tests/EventTripPreUpgradeVersionPolicyTest.java',
    ROOT/'tests/ProtectedBackendCapabilityPolicyTest.java',
    ROOT/'tests/GmailReadOnlyPolicyTest.java',
    REPO/'GOOGLE_GMAIL_SETUP.md',
]
for p in required:
    assert p.is_file(), p

gradle_properties=(APP/'gradle.properties').read_text(encoding='utf-8')
assert re.search(r'^android\.useAndroidX\s*=\s*true\s*$', gradle_properties, re.MULTILINE), \
       'AndroidX dependencies require android.useAndroidX=true'

discovery_button=(APP/'app/src/main/java/com/kiraworld/sarahtravel/ProactiveDiscoveryButton.java').read_text(encoding='utf-8')
assert 'extends Button' in discovery_button, 'discovery button must use the platform Button available in this package'
assert 'androidx.appcompat' not in discovery_button, 'AppCompat is not a declared Android dependency'

legacy_ci=(REPO/'.github/workflows/sarah-2-2-ci.yml').read_text(encoding='utf-8')
for phrase in (
    'working-directory: windows-companion',
    'python -m pytest -q tests',
    "throw 'Windows source tests failed.'",
    "throw 'Windows source compilation failed.'",
):
    assert phrase in legacy_ci, f'legacy Windows CI fail-closed contract missing: {phrase}'

for p in (APP/'app/src/main/res').rglob('*.xml'):
    root=ET.parse(p).getroot()
    ids=[]
    for node in root.iter():
        value=node.attrib.get('{http://schemas.android.com/apk/res/android}id','')
        if value:
            assert value not in ids, f'duplicate Android ID {value} in {p}'
            ids.append(value)
ET.parse(APP/'app/src/main/AndroidManifest.xml')
json.loads((ROOT/'sarah_phone_profile.json').read_text())

prompt=(APP/'app/src/main/java/com/kiraworld/sarahtravel/SarahPromptBuilder.java').read_text(encoding='utf-8')
for phrase in ['talk about anything','pre-request route plan','not proof of the provider','persisted runnable job','age-appropriate','offline']:
    assert phrase.lower() in prompt.lower(), phrase

main=(APP/'app/src/main/java/com/kiraworld/sarahtravel/MainActivity.java').read_text(encoding='utf-8')
for phrase in ['RecognizerIntent','ACTION_PICK_IMAGES','MemoryExtractor.extract','CloudVoiceClient.speak','showCalmMenu','startTriviaGame','connectedReplyWithRetry','ensureApproximateAreaForTurn','finalTurnRoute','ReplyTruthGuard.enforce','VoiceRoutePolicy.shouldAttemptPremium','connected.hasVerifiedWebReceipt()','TextTurnReceipt.build','findViewById(R.id.bottomNavigation)','findViewById(R.id.bottomControls)','actualProvider = connected.provider','actualModel = connected.model','BOUNDED_LOCAL_PLANNING_DRAFT','lower.contains("cheapest")','TravelSearchQueryPolicy.build','ownerSourceDetails','tap for source details','turnId','OfflineTuringPolicy.answer','PublicOnlineFallback.answerResult','sourceBackedEvent.turnRoute()','connectedRouteProven = true','if (changed) connectedRouteProven = false','Last reply:','Next:','ProtectedBackendCapabilities.voiceReady(this)','Generating Sarah’s online voice']:
    if phrase == 'findViewById(R.id.bottomNavigation)':
        continue  # Deliberately removed from the conversation-first owner surface.
    assert phrase in main, phrase
assert 'if (connected) lastSmartCallFailed = false' not in main

onboarding=(APP/'app/src/main/java/com/kiraworld/sarahtravel/OnboardingActivity.java').read_text(encoding='utf-8')
assert 'Age, birth year, or skip' in onboarding
assert 'Phone voice ready as fallback' in onboarding
assert 'ElevenLabsVoiceConfig.isConfigured()' not in onboarding
database=(APP/'app/src/main/java/com/kiraworld/sarahtravel/SarahDatabase.java').read_text(encoding='utf-8')
assert 'DB_VERSION = 11' in database and 'age_known' in database and 'route TEXT' in database
for phrase in ['person_key TEXT NOT NULL','destination_knowledge_attempts','PENDING_NOT_SCHEDULED','PENDING_SCHEDULED','recordKnowledgeAttempt','markKnowledgePackScheduled']:
    assert phrase in database, phrase
people=(APP/'app/src/main/java/com/kiraworld/sarahtravel/PersonProfileStore.java').read_text(encoding='utf-8')
assert 'ProfileMigrationPolicy.ownerAgeKnown' in people
assert 'parseInt(ownerProfile.get("age"), 18)' not in people
for phrase in ['DB_VERSION = 2','person_memory_provenance','preserveMemoryProvenance','original_created_at','placeholder_owner_merge']:
    assert phrase in people, phrase
demo=(APP/'app/src/main/java/com/kiraworld/sarahtravel/DemoSarah.java').read_text(encoding='utf-8')
assert 'lower.matches(".*\\\\bai\\\\b.*")' in demo, \
        'DemoSarah must recognize AI only as a complete term'
assert '"technology", "ai", "robot"' not in demo, \
        'DemoSarah must not classify email/air/train as AI by substring'
assert 'I’m with you. We can stay with this subject.' not in demo
destination_parser=(APP/'app/src/main/java/com/kiraworld/sarahtravel/DestinationParser.java').read_text(encoding='utf-8')
assert 'traveling to|travelling to' in destination_parser
assert 'KNOWN.put("New Zealand", new String[]{"new zealand", "aotearoa"})' in destination_parser
planner=(APP/'app/src/main/java/com/kiraworld/sarahtravel/AgenticTravelPlanner.java').read_text(encoding='utf-8')
assert 'i am traveling to' in planner and 'i am travelling to' in planner
assert 'I have not read your mailbox in this turn' in planner
main_layout=(APP/'app/src/main/res/layout/activity_main.xml').read_text(encoding='utf-8')
for owner_control in ['ExploreButton', 'TravelHubButton', 'ProactiveDiscoveryButton', 'TrustedSyncButton']:
    assert owner_control in main_layout, owner_control + ' must remain visible from the main owner surface'
manifest=(APP/'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
assert 'ACCESS_COARSE_LOCATION' in manifest
assert 'application/pdf' in manifest
assert 'android:usesCleartextTraffic="false"' in manifest
settings=(APP/'app/src/main/res/layout/activity_settings.xml').read_text(encoding='utf-8')
assert settings.count('@+id/nearbyDiscoveryCheck') == 1
assert settings.count('@+id/nearbyAreaInput') == 1
assert settings.count('@+id/buildDetailsText') == 1
assert 'android:id="@+id/autoDeviceSyncCheck"' in settings and 'android:checked="false"' in settings
auto_research_xml=settings.split('android:id="@+id/autoResearchCheck"',1)[1].split('/>',1)[0]
assert 'android:checked="false"' in auto_research_xml
for group in ['Profile','Location','Email and bookings','Online and offline mind','Sarah\'s voice','Devices and synchronization','Memory and privacy']:
    assert group in settings, group
build=(APP/'app/build.gradle').read_text(encoding='utf-8')
assert "versionCode 27" in build
assert "versionName '2.5-r3-owner-repair'" in build
assert "applicationId 'com.kiraworld.sarahtravel'" in build
assert 'com.kiraworld.sarahtravel.r2candidate' not in build
assert 'com.kiraworld.sarahtravel.r3candidate' not in build
assert "buildConfigField 'String', 'SARAH_TAVILY_API_KEY', '\"\"'" in build
assert "System.getenv('SARAH_TAVILY_API_KEY')" not in build
assert "androidx.media3:media3-exoplayer:1.9.4" in build
sync_importer=(APP/'app/src/main/java/com/kiraworld/sarahtravel/SarahSyncImporter.java').read_text(encoding='utf-8')
assert 'row.optString("route", TurnRoute.UNKNOWN_LEGACY)' in sync_importer
assert 'people.stageOwnerCandidate(incomingProfile)' in sync_importer
assert 'store.addSynced(' in sync_importer and 'row.optLong("source_time", 0L)' in sync_importer
backend=(APP/'app/src/main/java/com/kiraworld/sarahtravel/SarahBackendClient.java').read_text(encoding='utf-8')
assert 'duplicateCurrentUser' in backend
for phrase in ['ConnectedTurnPolicy.CONNECT_TIMEOUT_MS','ConnectedTurnPolicy.READ_TIMEOUT_MS','MAX_RESPONSE_CHARS','response exceeded the bounded response limit']:
    assert phrase in backend, phrase
for phrase in ['actualProvider.isEmpty()','actualModel.isEmpty()','onlineReceipt instanceof Boolean','required actual provider/model/online route receipt']:
    assert phrase in backend, phrase
assert 'json.optString("provider", providerId)' not in backend
assert 'json.optBoolean("online", true)' not in backend
assert 'ACTIVE_CONNECTIONS' in backend and 'public static void cancel(Thread worker)' in backend
turn_policy=(APP/'app/src/main/java/com/kiraworld/sarahtravel/ConnectedTurnPolicy.java').read_text(encoding='utf-8')
def int_constant(source, name):
    match=re.search(rf'{name}\s*=\s*([0-9_]+)', source)
    assert match is not None, name
    return int(match.group(1).replace('_',''))
connect_timeout=int_constant(turn_policy, 'CONNECT_TIMEOUT_MS')
read_timeout=int_constant(turn_policy, 'READ_TIMEOUT_MS')
attempts=int_constant(turn_policy, 'ATTEMPTS_PER_TURN')
retry_backoff=int_constant(turn_policy, 'RETRY_BACKOFF_MS')
assert attempts == 2
assert attempts * (connect_timeout + read_timeout) + retry_backoff <= 15_000
truth_guard=(APP/'app/src/main/java/com/kiraworld/sarahtravel/ReplyTruthGuard.java').read_text(encoding='utf-8')
assert 'no durable job was created' in truth_guard
destination=(APP/'app/src/main/java/com/kiraworld/sarahtravel/DestinationKnowledgeCoordinator.java').read_text(encoding='utf-8')
assert 'getOrDefault("age_group", "unknown_use_child_safe_mode")' in destination
assert 'DestinationSourcePolicy.canPersistReadyPack' in destination
events=(APP/'app/src/main/java/com/kiraworld/sarahtravel/EventResearchCoordinator.java').read_text(encoding='utf-8')
assert '!connected.hasVerifiedWebReceipt()' in events
assert 'leaseStillValid(context, store, ownerLease)' in events
assert '!connected.hasSourceUrl(sourceUrl)' in events
for phrase in ['if (!leaseStillValid(context, store, ownerLease)) break;',
               '|| !store.updateEventResearch(',
               'if (!leaseStillValid(context, store, ownerLease)) return false;',
               'leaseStillValidForProfileKey',
               'ownerLease.requireActive()']:
    assert phrase in events, phrase
planner=(APP/'app/src/main/java/com/kiraworld/sarahtravel/AgenticTravelPlanner.java').read_text(encoding='utf-8')
assert 'TravelPlanningConversationPolicy.asksForCurrentCost' in planner
assert 'TravelPlanningConversationPolicy.shortTripReply' in planner
for phrase in ['TravelPlanningConversationPolicy.explicitlyPlansTrip','SAVE_PLANNED_TRIP','memoryConsent','conversation-confirmed planned destination; dates not set']:
    assert phrase in planner, phrase
for phrase in ['public final boolean monitoringRequested',
               'eventIntent.monitoringRequested',
               'without silently turning on background monitoring']:
    assert phrase in planner, phrase
global_action_policy=(APP/'app/src/main/java/com/kiraworld/sarahtravel/AgenticGlobalActionPolicy.java').read_text(encoding='utf-8')
for action_type in ['SAVE_WISH','CREATE_DEAL_WATCH','UPDATE_DESTINATION_FOCUS',
                    'SET_FLEXIBLE_DATES','SAVE_JOURNEY_PLAN','CREATE_MOBILITY_WATCH']:
    assert f'AgenticTravelPlanner.{action_type}.equals(actionType)' in global_action_policy, action_type
action_executor=(APP/'app/src/main/java/com/kiraworld/sarahtravel/AgenticActionExecutor.java').read_text(encoding='utf-8')
owner_gate=action_executor.index('AgenticGlobalActionPolicy.requiresExactConfirmedOwner(action.type)')
first_action=action_executor.index('if (AgenticTravelPlanner.QUEUE_KNOWLEDGE_PACK.equals(action.type))')
assert owner_gate < first_action
for phrase in ['ConfirmedOwnerLease.capture(context)',
               '!isExactConfirmedOwner(ownerLease, profile)',
               'failedForegroundReceipts.add(',
               'AgenticGlobalActionPolicy.rejectedReceipt(',
               'ownerLease.requireActive()',
               'continue;']:
    assert phrase in action_executor[0:first_action] or phrase in action_executor, phrase
deal_scheduler=action_executor[action_executor.index('boolean monitoringRunnable ='):
                               action_executor.index('boolean eventSchedulerAccepted = false;')]
for phrase in ['monitoringRunnable && exactOwnerAtSchedulerBoundary',
               'DealWatchScheduler.ensureScheduled(context)',
               'DealWatchScheduler.runSoon(context)',
               'globalWatchSchedulerAccepted = periodicAccepted',
               'exact confirmed owner lease changed before scheduling']:
    assert phrase in deal_scheduler, phrase
knowledge_scheduler=action_executor[
    action_executor.index('if (AgenticTravelPlanner.QUEUE_KNOWLEDGE_PACK.equals(action.type)'):
    action_executor.index('} else if (AgenticTravelPlanner.SAVE_WISH.equals(action.type))')]
for phrase in ['promotedBeforeScheduling\n                            && isExactConfirmedOwner(ownerLease, profile)',
               'ownerLeaseRevokedBeforeScheduling = true',
               'db.markKnowledgePackNotScheduled(',
               'exact confirmed owner lease changed before scheduling']:
    assert phrase in knowledge_scheduler, phrase
backend_worker=(REPO/'services/sarah-model-proxy/src/index.js').read_text(encoding='utf-8')
assert 'annotation.type === "url_citation"' in backend_worker
assert 'item.status === "completed"' in backend_worker
sponsors=(APP/'app/src/main/java/com/kiraworld/sarahtravel/SponsorConnectionsActivity.java').read_text(encoding='utf-8')
assert 'GmailTravelConnection.privacySummary()' in sponsors
assert 'GmailTokenVault' in sponsors and 'GmailAuthorizationActivity.class' in sponsors
assert 'BookingImportActivity.class' in sponsors
migrator=(APP/'app/src/main/java/com/kiraworld/sarahtravel/OwnerProfileDataMigrator.java').read_text(encoding='utf-8')
for store in ['TripPlanStore','StayRequestStore','TravelerNeedsStore','LoyaltyVaultStore','RoadTripProfileStore','HotelSearchState','SarahLocationStore','ProactiveDiscoveryStore','ProactiveResearchReceiptStore','VoiceReceiptStore','EventTripStore']:
    assert store in migrator, store
assert 'claimLegacyOwnerData' in migrator
migration_archive=(APP/'app/src/main/java/com/kiraworld/sarahtravel/ProfileMigrationArchiveStore.java').read_text(encoding='utf-8')
for phrase in ['profile_migration_archive_v1','preserveCollision','containsExact','source_payload','target_payload','putVerified']:
    assert phrase in migration_archive, phrase
secure_vault=(APP/'app/src/main/java/com/kiraworld/sarahtravel/SecureProfileVault.java').read_text(encoding='utf-8')
assert 'removeVerified' in secure_vault and '.commit()' in secure_vault
traveler_needs=(APP/'app/src/main/java/com/kiraworld/sarahtravel/TravelerNeedsStore.java').read_text(encoding='utf-8')
loyalty_store=(APP/'app/src/main/java/com/kiraworld/sarahtravel/LoyaltyVaultStore.java').read_text(encoding='utf-8')
hotel_state=(APP/'app/src/main/java/com/kiraworld/sarahtravel/HotelSearchState.java').read_text(encoding='utf-8')
for source in [traveler_needs, loyalty_store, hotel_state]:
    assert 'ProfileMigrationArchiveStore.preserveCollision' in source
    assert 'ProfileMigrationArchiveStore.containsExact' in source
assert 'migratedRecordId' in loyalty_store and 'verifiedPayloads.containsAll(payloads)' in loyalty_store
assert 'expectedMergedSnapshot.equals(snapshot(preferences, newPrefix))' in hotel_state
cloud_voice=(APP/'app/src/main/java/com/kiraworld/sarahtravel/CloudVoiceClient.java').read_text(encoding='utf-8')
assert 'synthesisStart' in cloud_voice and 'playbackStart' in cloud_voice and 'playbackEnd' in cloud_voice
for phrase in ['REQUEST_SEQUENCE','superseded_before_playback','_started_interrupted','Speech text was empty']:
    assert phrase in cloud_voice, phrase
assert 'public static void cancel()' in cloud_voice
for phrase in [
        'ProgressiveMediaSource', 'ResolvingDataSource.Factory',
        '.setHttpMethod(DataSpec.HTTP_METHOD_POST)', '.setHttpBody(request.body)',
        'new DefaultLoadErrorHandlingPolicy(0)', 'Duplicate voice synthesis connection was blocked',
        'firstNetworkByte', 'playerReady', 'responseComplete', 'MAX_STREAM_BYTES',
        'X-Sarah-Voice-Route', 'elevenlabs-protected',
        'Protected voice response did not prove the approved ElevenLabs route']:
    assert phrase in cloud_voice, phrase
for removed_full_buffer_path in [
        'ByteArrayOutputStream', 'FileOutputStream', 'MediaPlayer',
        'getCacheDir()', 'sarah_elevenlabs_', 'responseBytes(']:
    assert removed_full_buffer_path not in cloud_voice, removed_full_buffer_path
assert 'â€¢' not in cloud_voice and 'â†’' not in cloud_voice
voice_router=(APP/'app/src/main/java/com/kiraworld/sarahtravel/SarahVoiceRouter.java').read_text(encoding='utf-8')
assert 'VoiceFallbackPolicy.shouldStartAndroidFallback' in voice_router
assert 'full phone replay suppressed' in voice_router
for phrase in ['"first_network_byte"', '"player_ready"', '"response_complete"']:
    assert phrase in main, phrase
for phrase in ['conversationExecutor','backgroundResearchExecutor','mediaExecutor','networkAttemptExecutor',
               'scheduleDeferredKnowledgeRefresh','pauseBackgroundResearchForOwnerTurn',
               'photoRequestSequence','requestMayApplyToSpeaker','invalidatePriorSpeakerWork',
               'recordActiveVoiceCancellation','unknown_cancelled','attemptFuture.get(']:
    assert phrase in main, phrase
assert 'executor.submit' not in main and 'executor.shutdownNow' not in main
voice_receipts=(APP/'app/src/main/java/com/kiraworld/sarahtravel/VoiceReceiptStore.java').read_text(encoding='utf-8')
for phrase in ['voice_receipt_event_','turn_id','commit()','moveProfile','scanEvents']:
    assert phrase in voice_receipts, phrase
booking_import=(APP/'app/src/main/java/com/kiraworld/sarahtravel/BookingImportActivity.java').read_text(encoding='utf-8')
for phrase in ['Intent.ACTION_OPEN_DOCUMENT','application/pdf','pending review','clearBookingImportsAndDerivatives','Connect Gmail read-only','getCanonicalFile()','count = in.read(buffer)) != -1','exact.delete()',
               'BookingImportTextPolicy.accepted', 'Review shared booking text',
               'Nothing has been saved or scheduled',
               'launchedFromShare && !externalShareReviewed',
               'boundPersonId', 'profileImportDirectory',
               'private quarantine item(s) remain for review',
               'Nothing was cleared. Sarah restored',
               'reviewExternallySharedFile(uri, declaredType)',
               'Nothing has been copied, saved, sent to a service, or scheduled',
               'MAX_SHARED_FILE_BYTES = 12_000_000',
               'PrivateContentSnapshot.capture(',
               'snapshot.approvedMimeType()',
               'ProfileMigrationPolicy.isConfirmedDisplayName',
               'requireConfirmedImportLease(ownerConfirmed)',
               'Exact private residual requiring review:',
               '".pending_image_" + UUID.randomUUID()',
               'cleanupImportArtifacts(derivative, stagingDirectory)',
               'derivative.getAbsolutePath()']:
    assert phrase in booking_import, phrase
private_snapshot=(APP/'app/src/main/java/com/kiraworld/sarahtravel/PrivateContentSnapshot.java').read_text(encoding='utf-8')
assert private_snapshot.count('resolver.openInputStream(uri)') == 1
for phrase in ['output.getFD().sync()', 'target.setReadOnly()', 'maximumBytes',
               'normalizeApprovedImageMime']:
    assert phrase in private_snapshot, phrase
clear_ui=booking_import.split('private void clearImports()',1)[1].split('private File profileImportDirectory',1)[0]
assert 'result.localPaths' not in clear_ui and '.delete()' not in clear_ui
assert 'clearBookingImportsAndReturnPaths' not in booking_import
booking_text_policy=(APP/'app/src/main/java/com/kiraworld/sarahtravel/BookingImportTextPolicy.java').read_text(encoding='utf-8')
for phrase in ['MAX_CHARS = 16_384', 'MAX_UTF8_BYTES = 32_768',
               'StandardCharsets.UTF_8', 'clean.length() <= MAX_CHARS']:
    assert phrase in booking_text_policy, phrase
gmail=(APP/'app/src/main/java/com/kiraworld/sarahtravel/GmailTravelConnection.java').read_text(encoding='utf-8')
assert 'implementationAvailable() { return true; }' in gmail
gmail_policy=(APP/'app/src/main/java/com/kiraworld/sarahtravel/GmailReadOnlyPolicy.java').read_text(encoding='utf-8')
gmail_client=(APP/'app/src/main/java/com/kiraworld/sarahtravel/GmailReadOnlyClient.java').read_text(encoding='utf-8')
gmail_activity=(APP/'app/src/main/java/com/kiraworld/sarahtravel/GmailAuthorizationActivity.java').read_text(encoding='utf-8')
gmail_worker=(APP/'app/src/main/java/com/kiraworld/sarahtravel/GmailTravelMonitorWorker.java').read_text(encoding='utf-8')
gmail_scheduler=(APP/'app/src/main/java/com/kiraworld/sarahtravel/GmailMonitorScheduler.java').read_text(encoding='utf-8')
gmail_vault=(APP/'app/src/main/java/com/kiraworld/sarahtravel/GmailTokenVault.java').read_text(encoding='utf-8')
manifest=(APP/'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
app_gradle=(APP/'app/build.gradle').read_text(encoding='utf-8')
assert 'https://www.googleapis.com/auth/gmail.readonly' in gmail_policy
assert 'MAX_CANDIDATES = 10' in gmail_policy and 'MAX_RESPONSE_BYTES = 512 * 1024' in gmail_policy
assert 'setRequestMethod("GET")' in gmail_client
assert 'format=full' in gmail_client and 'fields=id,threadId,internalDate,snippet,payload(headers)' in gmail_client
assert 'bounded_snippet_read' in gmail_client and 'message_modified' in gmail_client
for forbidden in ['setRequestMethod("POST")','setRequestMethod("PUT")',
                  'setRequestMethod("PATCH")','setRequestMethod("DELETE")',
                  'requestOfflineAccess','client_secret']:
    assert forbidden not in gmail_client + gmail_activity + gmail_worker, forbidden
for phrase in ['"body_read", false','"message_modified", false',
               '"fetched_at_epoch_ms"','"message_id"','"exact_query"','"source_endpoint"']:
    assert phrase in gmail_client, phrase
assert 'AES/GCM/NoPadding' in gmail_vault and 'AndroidKeyStore' in gmail_vault
assert 'refresh_token' not in gmail_vault.lower()
assert 'setOptOutIncludingGrantedScopes(true)' in gmail_activity and 'setOptOutIncludingGrantedScopes(true)' in gmail_worker
assert 'monitoringEnabled(profileId)' in gmail_worker and 'setRequiresBatteryNotLow(true)' in gmail_scheduler
assert 'GmailAuthorizationActivity' in manifest and 'android:exported="false"' in manifest
assert 'play-services-auth:21.6.0' in app_gradle and 'work-runtime:2.11.2' in app_gradle
onboarding=(APP/'app/src/main/java/com/kiraworld/sarahtravel/OnboardingActivity.java').read_text(encoding='utf-8')
auto_pair=(APP/'app/src/main/java/com/kiraworld/sarahtravel/SarahAutoPairCoordinator.java').read_text(encoding='utf-8')
assert 'else beginDiscoveryBeforeProfile();' in onboarding
assert 'runBeforeProfileQuestions(' in onboarding and 'runBeforeProfileQuestions(' in auto_pair
assert onboarding.index('beginDiscoveryBeforeProfile();') < onboarding.index('What is your name?')
for phrase in ['if (!SarahDeviceDiscovery.isOnWifi(activity))',
               '"No - continue setup"',
               'prompt.setOnCancelListener(dialog -> continueOnce.run())',
               'activity.runOnUiThread(continueOnce)']:
    assert phrase in auto_pair, phrase
memory_to_email=onboarding.split('case STEP_MEMORY:',1)[1].split('case STEP_EMAIL:',1)[0]
assert memory_to_email.index('persistProfile();') < memory_to_email.index('step = STEP_EMAIL;')
assert 'Would you like to connect Gmail now' in onboarding and 'no, or later' in onboarding
assert 'optBoolean("monitoring_enabled", false)' in gmail_vault
assert 'canMonitor(true, false)' in (ROOT/'tests/SarahR2PolicyTest.java').read_text(encoding='utf-8')
gmail_setup=(REPO/'GOOGLE_GMAIL_SETUP.md').read_text(encoding='utf-8')
for phrase in ['gmail.readonly','com.kiraworld.sarahtravel','supervised read-only test','Disconnect','Clear imported booking data']:
    assert phrase.lower() in gmail_setup.lower(), phrase
windows_core=(REPO/'windows-companion/sarah_core.py').read_text(encoding='utf-8')
for phrase in ['needs_owner_identity_confirmation','actual_provider=','web_search_applied=','text_latency_ms=','ONLINE_CONNECTED']:
    assert phrase in windows_core, phrase
windows_ui=(REPO/'windows-companion/sarah_windows.py').read_text(encoding='utf-8')
for phrase in ['needs_owner_identity_confirmation(profile)','synthesis_start','playback_end','total_voice_latency_ms']:
    assert phrase in windows_ui, phrase
city=(APP/'app/src/main/java/com/kiraworld/sarahtravel/CityVisitPlanner.java').read_text(encoding='utf-8')
brain=(APP/'app/src/main/java/com/kiraworld/sarahtravel/TravelBrainCore.java').read_text(encoding='utf-8')
assert 'getOrDefault("age_group", "adult")' not in city + brain
assert 'MaturityAccessPolicy' in city and 'MaturityAccessPolicy' in brain

tavily=(APP/'app/src/main/java/com/kiraworld/sarahtravel/TavilyClient.java').read_text(encoding='utf-8')
for phrase in ['SarahModelConfig.backendUrl()','/search','Authorization','SarahModelConfig.backendToken()']:
    assert phrase in tavily, phrase
assert 'BuildConfig.SARAH_TAVILY_API_KEY' not in tavily
assert 'MAX_RESPONSE_BYTES = 1_048_576' in tavily and 'c.disconnect()' in tavily
worker=(REPO/'services/sarah-model-proxy/src/index.js').read_text(encoding='utf-8')
for phrase in ['url.pathname === "/search"','runProtectedSearch','current_source_not_configured','body.search_query',
               'url.pathname === "/capabilities"',
               'constantTimeEqual(suppliedToken, env.SARAH_BACKEND_TOKEN)']:
    assert phrase in worker, phrase
workflow=(REPO/'.github/workflows/sarah-2.5-online-judge-build.yml').read_text(encoding='utf-8')
for phrase in ['TAVILY_API_KEY: ${{ secrets.SARAH_TAVILY_API_KEY }}','Run R3 source and pure-policy acceptance','CURRENT_SOURCE_ACCEPTANCE_BLOCKED','SARAH_TAVILY_API_KEY: \'\'','sarah-contextual-chat-request.json','72_HOUR_EVENT_CANDIDATE_PENDING_PHYSICAL_ACCEPTANCE','72-HOUR EVENT CANDIDATE','Sarah-Morgan-Event-Candidate-72H.apk','SarahMorgan-Event-Candidate-72H-Setup.exe','physical_galaxy_a17_acceptance','full_16_gate_owner_acceptance','physical_8gb_laptop_acceptance','media3_progressive_one_shot_post','protected_voice_route_receipt_required','first_network_byte','player_ready','response_complete','pending_physical_galaxy_a17_measurement','sarah-r3-${{ github.run_id }}-${{ github.run_attempt }}','id: signing_cache','steps.signing_cache.outputs.cache-hit','SARAH_R1_SIGNING_CERT_SHA256','SARAH_R1_APK_SHA256','apksigner verify --print-certs','BuildConfig.java','build/r3-apk-secret-scan','r1_signer_sha256','r3_signer_sha256','same-package-in-place-only',
               "isoformat(timespec='milliseconds').replace('+00:00', 'Z')",
               'EVENT_AUTH_EXPIRES_UTC: ${{ steps.event_auth.outputs.expires_utc }}',
               'EVENT_AUTH_TOKEN_SHA256: ${{ steps.event_auth.outputs.token_sha256 }}',
               'EVENT_AUTH_CONTEXT_SHA256: ${{ steps.event_auth.outputs.context_sha256 }}',
               'acceptance_probe=production_modelclient',
               'sarah-capabilities-absent.json','sarah-capabilities-wrong.json',
               'sarah-capabilities-exact.json',
               'Authenticated Sarah capability contract passed']:
    assert phrase in workflow, phrase
assert 'maximum_read_seconds = 18.0 if current_source_request else 12.0' in (
    REPO / 'windows-companion' / 'sarah_core.py'
).read_text(encoding='utf-8')
assert 'strings Sarah-Morgan-2.5-R3-CURRENT-OWNER-TEST.apk' not in workflow
for phrase in ['d49b6dea8f8ddb332c170abd2d79240de011d302bdbec8a732f783910134c63c',
               'be67ceb0adf6d920532bb46a8b79a2be4b6c98dca20a5765f33a70489204b314',
               'live_vision_solid_red_jpeg_smoke_test','candidate_worker_deployments',
               "Retire only this failed run's unpreserved candidate Worker",
               "steps.upload_candidate.outcome != 'success'"]:
    assert phrase in workflow, phrase
assert 'secrets.SARAH_R1_SIGNING_CERT_SHA256' not in workflow
assert 'secrets.SARAH_R1_APK_SHA256' not in workflow

backup=(APP/'app/src/main/res/xml/backup_rules.xml').read_text(encoding='utf-8')
extraction=(APP/'app/src/main/res/xml/data_extraction_rules.xml').read_text(encoding='utf-8')
for phrase in ['booking_imports/','booking_import_quarantine/','sarah_event_trips.db','sarah_profile_vault.xml','sarah_voice_receipts.xml']:
    assert phrase in backup and extraction, phrase

memory=(APP/'app/src/main/java/com/kiraworld/sarahtravel/MemoryExtractor.java').read_text(encoding='utf-8')
assert 'addDestinationWishes' not in memory
offline_turing=(APP/'app/src/main/java/com/kiraworld/sarahtravel/OfflineTuringPolicy.java').read_text(encoding='utf-8')
for phrase in ['what(?:\'s| is) your name','who am i','online or offline','connected online mind','did not answer']:
    assert phrase in offline_turing, phrase
public_source=(APP/'app/src/main/java/com/kiraworld/sarahtravel/PublicSourceResult.java').read_text(encoding='utf-8')
for phrase in ['https://','PUBLIC_SOURCE_TOOL_RESULT','TOOL_UNAVAILABLE','ownerSourceDetails']:
    assert phrase in public_source, phrase
public_fallback=(APP/'app/src/main/java/com/kiraworld/sarahtravel/PublicOnlineFallback.java').read_text(encoding='utf-8')
assert 'will retry' not in public_fallback.lower()
owner_correction=(APP/'app/src/main/java/com/kiraworld/sarahtravel/OwnerIdentityCorrectionActivity.java').read_text(encoding='utf-8')
assert 'markOwnerMoveComplete' in owner_correction and 'if (!moved' in owner_correction
assert 'mergePlaceholderSpeakers' not in owner_correction
assert 'OwnerProfileDataMigrator.claimLegacyOwnerData' in owner_correction

event_store=(APP/'app/src/main/java/com/kiraworld/sarahtravel/EventTripStore.java').read_text(encoding='utf-8')
for phrase in [
        'DB_VERSION = 2', 'LEGACY_OWNER_UNASSIGNED',
        'migrateV1Losslessly', 'Event-trip v1 migration exact-row verification failed',
        'migrationProjectionMissing',
        'person_profile_key TEXT NOT NULL', 'monitor_enabled INTEGER NOT NULL DEFAULT 0',
        'active=1 AND monitor_enabled=1', 'moveProfileKey',
        'Event-trip profile move verification failed',
        'clearBookingImportsAndDerivatives', 'SELECT id,local_path FROM booking_imports ',
        'EventTripPreUpgradeBackupGate.ensure(appContext)',
        'BOOKING_QUARANTINE_ROOT', 'TOMBSTONE.json',
        'BOOKING_CLEAR_JOURNAL = "JOURNAL.jsonl"',
        'reconcileBookingClearOperations',
        'sarah-booking-clear-journal-v2',
        'FILE_MOVE_INTENT', 'FILE_QUARANTINED',
        'RECOVERY_FILE_RESTORE_INTENT', 'RECOVERY_FILE_RESTORED',
        'root.put("booking_rows", rowItems)',
        'bookingRowsMatchSnapshot(db, bookingRows)',
        'BOOKING_RECOVERY_LEGACY_ROW_IDENTITY_INCOMPLETE_REVIEW_REQUIRED',
        'BOOKING_RECOVERY_COMMITTED_PRIVATE_RESIDUAL_REVIEW_REQUIRED',
        'FILES_QUARANTINED_DATABASE_NOT_COMMITTED',
        'BOOKING_ROWS_CLEARED_RESIDUAL_PRIVATE_QUARANTINE',
        'restoreQuarantinedDerivatives', 'checkedDeleteExactFile',
        'isOwnedBookingFilePath', 'id=? AND person_profile_key=?']:
    assert phrase in event_store, phrase
assert event_store.count('person_profile_key TEXT NOT NULL') >= 3
assert 'public EventTripStore(Context context)' not in event_store
assert 'clearBookingImportsAndReturnPaths' not in event_store
assert 'new Object[]{legacy}' in event_store
assert event_store.count('countRows(db, "event_') >= 6
event_constructor=event_store.split(
    'private EventTripStore(Context context, String key, boolean alreadyNormalized)',1
)[1].split('@Override',1)[0]
assert event_constructor.index('EventTripPreUpgradeBackupGate.ensure(appContext)') < event_constructor.index('profileKey =')
assert 'getWritableDatabase()' not in event_constructor and 'getReadableDatabase()' not in event_constructor
clear_contract=event_store.split('public BookingClearResult clearBookingImportsAndDerivatives()',1)[1].split('/** Compatibility count',1)[0]
assert clear_contract.index('PLANNED_BEFORE_FILE_MOVE') < clear_contract.index('checkedMoveToQuarantine')
assert clear_contract.index('checkedMoveToQuarantine') < clear_contract.index('db.delete(')
assert clear_contract.index('db.delete(') < clear_contract.index('db.setTransactionSuccessful()')
assert clear_contract.index('FILE_MOVE_INTENT') < clear_contract.index('checkedMoveToQuarantine')
assert clear_contract.index('checkedMoveToQuarantine') < clear_contract.index('FILE_QUARANTINED')
recovery_contract=event_store.split('public BookingRecoveryResult reconcileBookingClearOperations()',1)[1].split('public BookingClearResult clearBookingImportsAndDerivatives()',1)[0]
assert 'checkedDeleteExactFile' not in recovery_contract
assert 'deleteCommittedQuarantine' not in recovery_contract
sarah_application=(APP/'app/src/main/java/com/kiraworld/sarahtravel/SarahApplication.java').read_text(encoding='utf-8')
assert 'reconcileInterruptedBookingClearAtStartup();' in sarah_application
assert sarah_application.index('reconcileInterruptedBookingClearAtStartup();') < sarah_application.index('ProtectedBackendCapabilities.refreshAsync(this)')

event_intent=(APP/'app/src/main/java/com/kiraworld/sarahtravel/EventTripIntentParser.java').read_text(encoding='utf-8')
for forbidden in [
        'new EventIntent(known.eventName, known.destination, true)',
        'new EventIntent("CES", "Las Vegas", true)',
        'monitor || looksLikeNamedEvent(event)']:
    assert forbidden not in event_intent, forbidden
assert '(?:for|about|of)' in event_intent
event_intent_tests=(ROOT/'tests/EventTripIntentParserTest.java').read_text(encoding='utf-8')
assert 'Cancel monitoring of Travel Hack NYC' in event_intent_tests
assert 'cancel-monitoring-of must not retain the preposition' in event_intent_tests
action_executor=(APP/'app/src/main/java/com/kiraworld/sarahtravel/AgenticActionExecutor.java').read_text(encoding='utf-8')
assert 'EventTripMonitoringPolicy.canEnable(\n                            action.monitoringRequested' in action_executor
booking_extraction=(APP/'app/src/main/java/com/kiraworld/sarahtravel/BookingExtractionCoordinator.java').read_text(encoding='utf-8')
for phrase in ['store.isOwnedBookingFilePath', 'ownerLease.requireActive()',
               '&& store.updateBookingExtraction(']:
    assert phrase in booking_extraction, phrase

location_turn=(APP/'app/src/main/java/com/kiraworld/sarahtravel/MainActivity.java').read_text(encoding='utf-8')
for phrase in ['pendingLocationPersonId','pendingLocationSpeaker','pendingLocationGeneration','sameActiveProfile','locationTurnActive','destroyed = true',
               'STATE_PENDING_LOCATION_MESSAGE', 'onSaveInstanceState',
               'CurrentLocationPolicy.SOURCE_DEVICE_RESOLVED',
               'runtime_current_area_source']:
    assert phrase in location_turn, phrase
location_store=(APP/'app/src/main/java/com/kiraworld/sarahtravel/SarahLocationStore.java').read_text(encoding='utf-8')
for phrase in ['_source', 'SOURCE_DEVICE_RESOLVED', 'SOURCE_MANUAL', 'SOURCE_UNKNOWN']:
    assert phrase in location_store, phrase
for phrase in ['settingsStatus(', 'SOURCE_DEVICE_RESOLVED', 'SOURCE_MANUAL']:
    assert phrase in (APP/'app/src/main/java/com/kiraworld/sarahtravel/CurrentLocationPolicy.java').read_text(encoding='utf-8'), phrase
travel_search=(APP/'app/src/main/java/com/kiraworld/sarahtravel/TravelSearchQueryPolicy.java').read_text(encoding='utf-8')
for phrase in ['runtime_current_area_source', 'Device-resolved approximate current area',
               'Owner-entered area for nearby search', 'unrecorded source']:
    assert phrase in travel_search, phrase

adaptive_research=(APP/'app/src/main/java/com/kiraworld/sarahtravel/AdaptiveResearchPlan.java').read_text(encoding='utf-8')
for phrase in ['Power Rangers filming locations in New Zealand','nearby_interest','MAX_PACKS_PER_RUN']:
    assert phrase in adaptive_research, phrase
research_receipt=(APP/'app/src/main/java/com/kiraworld/sarahtravel/ProactiveResearchReceiptStore.java').read_text(encoding='utf-8')
for phrase in ['RUNNING','SUCCEEDED','FAILED','source_result_count','saved_count','CurrentLocationPolicy.profileKey','commit()','moveProfile','ProfileMigrationArchiveStore.preserveCollision']:
    assert phrase in research_receipt, phrase
discovery_store=(APP/'app/src/main/java/com/kiraworld/sarahtravel/ProactiveDiscoveryStore.java').read_text(encoding='utf-8')
for phrase in ['DB_VERSION = 2','profile_key','UNIQUE(profile_key,url)','claimLegacyProfile','exactProfileKey','public boolean addSynced','origin time unavailable','moveProfile','ProfileMigrationArchiveStore.preserveCollision']:
    assert phrase in discovery_store, phrase
proactive=(APP/'app/src/main/java/com/kiraworld/sarahtravel/ProactiveDiscoveryCoordinator.java').read_text(encoding='utf-8')
for phrase in ['AdaptiveResearchPlan.build','ProactiveResearchReceiptStore.started','ProactiveResearchReceiptStore.succeeded','ProactiveResearchReceiptStore.failed']:
    assert phrase in proactive, phrase
discovery_migration=(APP/'app/src/main/java/com/kiraworld/sarahtravel/ProactiveDiscoveryStore.java').read_text(encoding='utf-8')
for phrase in ['moveProfileKeys(','ProfileMigrationArchiveStore.preserveCollision','source rows were retained']:
    assert phrase in discovery_migration, phrase
claim_body=discovery_migration.split('public void claimLegacyProfile',1)[1].split('public List<Map<String, String>> list',1)[0]
placeholder_body=discovery_migration.split('public void mergePlaceholderSpeakers',1)[1].split('private static String exactProfileKey',1)[0]
assert 'INSERT OR IGNORE INTO discoveries' not in claim_body
assert 'INSERT OR IGNORE INTO discoveries' not in placeholder_body

owner_ui=(APP/'app/src/main/res/layout/activity_settings.xml').read_text(encoding='utf-8')
for forbidden in ['hackathon prototype','team-owned sources','protected team online mind','cloudflare workers ai','provider keys or model names']:
    assert forbidden not in owner_ui.lower(), forbidden
settings_source=(APP/'app/src/main/java/com/kiraworld/sarahtravel/SettingsActivity.java').read_text(encoding='utf-8')
for phrase in ['BuildConfig.SARAH_BUILD_COMMIT','local/unbound build','TrustedDeviceStore.hasPeers',
               'ElevenLabs Sarah voice ready','R1 backup manifest SHA-256',
               'Event-trip upgrade safety']:
    assert phrase in settings_source, phrase
for phrase in ['KnowledgePackSchedulingPolicy.settingsCanEnable',
                'KnowledgePackSchedulingPolicy.persistEnabled',
                'autoResearch.setChecked(locationStore.backgroundResearchEnabled(activePersonId))',
                'autoResearch.setEnabled(researchOwner && researchMemoryConsent)',
                'your saved opt-in is preserved, but no background work can run now',
               'BackgroundResearchPolicy.DEFAULT_BACKGROUND_MONITORING_ENABLED',
               '.putBoolean("deal_alerts_enabled", monitoringOptIn)',
               'hasEligibleEventMonitoringWork(TavilyClient.configured())',
               'event.getOrDefault("monitor_enabled", "no")']:
    assert phrase in settings_source, phrase
onboarding_source=(APP/'app/src/main/java/com/kiraworld/sarahtravel/OnboardingActivity.java').read_text(encoding='utf-8')
assert 'verified ElevenLabs voice available online' in onboarding_source
assert 'Robert' not in onboarding_source and 'Newark' not in onboarding_source
hackathon_demo=(APP/'app/src/main/java/com/kiraworld/sarahtravel/HackathonDemoActivity.java').read_text(encoding='utf-8')
assert 'Robert' not in hackathon_demo
travel_hub=(APP/'app/src/main/java/com/kiraworld/sarahtravel/TravelHubActivity.java').read_text(encoding='utf-8')
assert 'TravelUi.makeSectionsCollapsible(root)' not in travel_hub and '"Add dates"' in travel_hub
assert '"Event partner connections"' in travel_hub and 'SponsorConnectionsActivity.class' in travel_hub
discovery=(APP/'app/src/main/java/com/kiraworld/sarahtravel/DiscoveryActivity.java').read_text(encoding='utf-8')
for phrase in ['availabilityStatus','owner_requested_immediate','ProactiveResearchReceiptStore.latest','Research did not complete','SafeAreaInsets.apply']:
    assert phrase in discovery, phrase
scheduler=(APP/'app/src/main/java/com/kiraworld/sarahtravel/ProactiveDiscoveryScheduler.java').read_text(encoding='utf-8')
assert 'public static boolean runSoon' in scheduler and 'JobScheduler.RESULT_SUCCESS' in scheduler

action_executor=(APP/'app/src/main/java/com/kiraworld/sarahtravel/AgenticActionExecutor.java').read_text(encoding='utf-8')
for phrase in ['KnowledgePackSchedulingPolicy.canSchedule','markKnowledgePackScheduled','pendingReceipts','validatedInternet',
               'BackgroundResearchPolicy.DEFAULT_BACKGROUND_MONITORING_ENABLED',
               'BackgroundResearchPolicy.monitoringCanRun',
               '(saved; automatic monitoring is off)']:
    assert phrase in action_executor, phrase
official_event=(APP/'app/src/main/java/com/kiraworld/sarahtravel/OfficialEventPageLookup.java').read_text(encoding='utf-8')
assert 'saved the event for the active profile without turning on background monitoring' in official_event
assert 'will watch the official page for the next announced dates' not in official_event
deal_worker=(APP/'app/src/main/java/com/kiraworld/sarahtravel/DealWatchWorker.java').read_text(encoding='utf-8')
for phrase in ['TavilyClient.configured()', 'SettingsActivity.getConversationMode(context)',
               'BackgroundResearchPolicy.DEFAULT_BACKGROUND_MONITORING_ENABLED',
               'BackgroundResearchPolicy.monitoringCanRun']:
    assert phrase in deal_worker, phrase
background_policy=(APP/'app/src/main/java/com/kiraworld/sarahtravel/BackgroundResearchPolicy.java').read_text(encoding='utf-8')
for phrase in ['DEFAULT_BACKGROUND_MONITORING_ENABLED = false',
               'monitoringCanRun(', 'explicitOptIn && monitoringRouteConfigured && eligibleWorkExists']:
    assert phrase in background_policy, phrase
knowledge_policy=(APP/'app/src/main/java/com/kiraworld/sarahtravel/KnowledgePackSchedulingPolicy.java').read_text(encoding='utf-8')
for phrase in ['settingsCanEnable(', 'persistEnabled(', 'settingsLabel(',
               'memory permission required', 'protected research setup required']:
    assert phrase in knowledge_policy, phrase
for phrase in ['BackgroundResearchPolicy.monitoringCanRun',
               'BackgroundResearchPolicy.DEFAULT_BACKGROUND_MONITORING_ENABLED']:
    assert phrase in main, phrase
knowledge_worker=(APP/'app/src/main/java/com/kiraworld/sarahtravel/DestinationKnowledgeCoordinator.java').read_text(encoding='utf-8')
for phrase in ['recordKnowledgeAttempt','KNOWLEDGE_RUNNING','"SUCCEEDED"','KNOWLEDGE_FAILED','throws Exception']:
    assert phrase in knowledge_worker, phrase

sync_exporter=(APP/'app/src/main/java/com/kiraworld/sarahtravel/SarahSyncExporter.java').read_text(encoding='utf-8')
for phrase in ['ImageSanitizer.syncDerivative','SyncPhotoPolicy.accepted','metadata_policy','jpeg_base64']:
    assert phrase in sync_exporter, phrase
assert 'Base64.encodeToString(Files.readAllBytes' not in sync_exporter
assert 'value.put("local_path"' not in sync_exporter
trusted_devices=(APP/'app/src/main/java/com/kiraworld/sarahtravel/TrustedDeviceStore.java').read_text(encoding='utf-8')
for phrase in ['SecureProfileVault.putVerified','SecureProfileVault.get','SecureProfileVault.removeVerified','ANDROID_KEYSTORE']:
    assert phrase in trusted_devices, phrase
mind_crypto=(APP/'app/src/main/java/com/kiraworld/sarahtravel/MindCrypto.java').read_text(encoding='utf-8')
assert 'throw new IllegalStateException("Private mind encryption failed' in mind_crypto

pure_dependencies = [
    'AgenticTravelPlanner.java','FinalDisplayedResponsePolicy.java',
    'KnowledgePackSchedulingPolicy.java','KnowledgeProfileKey.java',
    'ConnectedTurnPolicy.java','SyncPhotoPolicy.java','VoiceFallbackPolicy.java',
    'TrustedLanEndpointPolicy.java','BookingImportTextPolicy.java',
]
for workflow_name in [
        'sarah-2.5-pr-validation.yml',
        'sarah-2.5-online-judge-build.yml']:
    workflow_text=(REPO/'.github/workflows'/workflow_name).read_text(encoding='utf-8')
    assert 'SarahR2PolicyTest.java' in workflow_text, workflow_name
    for dependency in pure_dependencies:
        assert dependency in workflow_text, f'{workflow_name}: {dependency}'

legacy_final=(REPO/'.github/workflows/sarah-2.5-final-release.yml').read_text(encoding='utf-8')
assert 'SarahR2PolicyTest.java' not in legacy_final, 'legacy final-release workflow must remain preserved'

for workflow_name in [
        'sarah-2.5-pr-validation.yml',
        'sarah-2.5-online-judge-build.yml']:
    workflow_text=(REPO/'.github/workflows'/workflow_name).read_text(encoding='utf-8')
    assert 'TurnLifecyclePolicy.java' in workflow_text, workflow_name
    assert 'EventTripPreUpgradeVersionPolicy.java' in workflow_text, workflow_name
    assert 'EventTripPreUpgradeVersionPolicyTest.java' in workflow_text, workflow_name
online_workflow=(REPO/'.github/workflows/sarah-2.5-online-judge-build.yml').read_text(encoding='utf-8')
for dependency in ['KnownEventCatalog.java','GenericEventReference.java']:
    assert dependency in online_workflow, dependency
build_apk_workflow=(REPO/'.github/workflows/build-apk.yml').read_text(encoding='utf-8')
for dependency in ['EventTripProfilePolicy.java','EventTripMonitoringPolicy.java',
                   'EventTripProfilePolicyTest.java',
                   'ProtectedBackendCapabilityPolicy.java',
                   'ProtectedBackendCapabilityPolicyTest.java',
                   'EventTripPreUpgradeVersionPolicy.java',
                   'EventTripPreUpgradeVersionPolicyTest.java']:
    assert dependency in build_apk_workflow, dependency

lan_policy=(APP/'app/src/main/java/com/kiraworld/sarahtravel/TrustedLanEndpointPolicy.java').read_text(encoding='utf-8')
for phrase in ['PORT = 8769','bytes[0] == 10','bytes[0] == 127','bytes[0] == 169 && bytes[1] == 254','bytes[0] == 192 && bytes[1] == 168','uniqueLocal','linkLocal']:
    assert phrase in lan_policy, phrase
trusted_sync=(APP/'app/src/main/java/com/kiraworld/sarahtravel/TrustedSyncClient.java').read_text(encoding='utf-8')
for phrase in ['TrustedLanEndpointPolicy.requireLocalHost','TrustedSyncProtocol.encrypt','TrustedSyncProtocol.signature','X-Sarah-Device-Token','MAX_RESPONSE_BYTES','isTransportAccepted() { return false; }','requireAcceptedTransport()','Device sync is disabled']:
    assert phrase in trusted_sync, phrase
pairing_protocol=(APP/'app/src/main/java/com/kiraworld/sarahtravel/SarahPairingProtocol.java').read_text(encoding='utf-8')
for phrase in ['sarah-device-pairing-x25519-sas-v1','SarahDevicePairingV1\\0',
               'x25519(byte[] scalarInput','hkdf(shared, salt',
               'approval_required_on_both_devices','localConfirmation(',
               'acceptPeerConfirmation(','Both devices must explicitly confirm',
               'invalid all-zero shared secret','cannot be replayed']:
    assert phrase in pairing_protocol, phrase
pairing_transport=(APP/'app/src/main/java/com/kiraworld/sarahtravel/SarahPairingTransport.java').read_text(encoding='utf-8')
for phrase in ['writeInt(encoded.length)','readInt()','MAX_FRAME_BYTES',
               'requireExactPeer','TrustedLanEndpointPolicy.requireLocalHost',
               'response does not match the discovered Sarah device',
               'session.localConfirmation','session.acceptPeerConfirmation']:
    assert phrase in pairing_transport, phrase
for forbidden in ['SarahSyncExporter','GmailTravelConnection','SarahModelConfig',
                  'CloudVoiceClient','MODEL_BACKEND','ELEVENLABS','X-Sarah-Device-Token']:
    assert forbidden not in pairing_transport, forbidden
device_discovery=(APP/'app/src/main/java/com/kiraworld/sarahtravel/SarahDeviceDiscovery.java').read_text(encoding='utf-8')
for phrase in ['DISCOVERY_PORT = 8771','SARAH_DISCOVER_V2',
               'sarah-device-discovery-v2','approval_required_on_both_devices',
               'expires > nowSeconds + 60L','TrustedLanEndpointPolicy.requireLocalHost']:
    assert phrase in device_discovery, phrase
trusted_devices=(APP/'app/src/main/java/com/kiraworld/sarahtravel/TrustedDeviceStore.java').read_text(encoding='utf-8')
for phrase in ['saveFinalizedPeer','SarahPairingProtocol.Credential',
               'pairing_protocol','ANDROID_KEYSTORE',
               'Only a finalized two-device X25519 pairing credential may be stored']:
    assert phrase in trusted_devices, phrase
trusted_sync_activity=(APP/'app/src/main/java/com/kiraworld/sarahtravel/TrustedSyncActivity.java').read_text(encoding='utf-8')
for phrase in ['Is this your device?','same short-lived code','approve it on both devices',
               'pairingPort <= 0','Nothing was shared',
               'encrypted preview','Nothing imports automatically',
               'Review continuity from selected device',
               'saveFinalizedPeer']:
    assert phrase in trusted_sync_activity, phrase
secure_sync=(APP/'app/src/main/java/com/kiraworld/sarahtravel/SecureSyncPreviewClient.java').read_text(encoding='utf-8')
for phrase in ['sarah-secure-sync-v2','owner_import_required',
               'rejectNonOwnerData','photos','mind_events','discoveries',
               'Pair and approve this exact Sarah device first']:
    assert phrase in secure_sync, phrase
sync_provenance=(APP/'app/src/main/java/com/kiraworld/sarahtravel/SyncImportProvenance.java').read_text(encoding='utf-8')
for phrase in ['OWNER_APPROVED_SECURE_SYNC_IMPORT','secure_sync_import_history.jsonl',
               'APPEND_NEW_KEEP_EXISTING_RECORD_CONFLICTS']:
    assert phrase in sync_provenance, phrase
reverse_sync=(APP/'app/src/main/java/com/kiraworld/sarahtravel/SarahReverseSyncResponder.java').read_text(encoding='utf-8')
for phrase in ['SarahPairingProtocol.respond','acceptPeerConfirmation','finalizeCredential',
               'saveFinalizedPeer','ANDROID_TO_WINDOWS_PULL_ONLY','owner_import_required',
               'secure-sync-request:','exportOwnerReview']:
    assert phrase in reverse_sync, phrase
pairing_test=(ROOT/'tests/SarahPairingProtocolTest.java').read_text(encoding='utf-8')
for phrase in ['exactPythonInteroperabilityVector','EXPECTED_SAS = "488550"',
               'twoExplicitApprovalsAreRequired','expiryTamperAndReplayFailClosed',
               'allZeroSharedSecretIsRejected']:
    assert phrase in pairing_test, phrase

all_android_java='\n'.join(
    path.read_text(encoding='utf-8')
    for path in (APP/'app/src/main/java').rglob('*.java'))
for unsupported in ['List.of(', 'List.copyOf(', 'Set.of(',
                    'toString(StandardCharsets.UTF_8)']:
    assert unsupported not in all_android_java, unsupported
assert re.search(
        r'getBoolean\(\s*"deal_alerts_enabled"\s*,\s*true\s*\)',
        all_android_java) is None

capability_policy=(APP/'app/src/main/java/com/kiraworld/sarahtravel/ProtectedBackendCapabilityPolicy.java').read_text(encoding='utf-8')
for phrase in ['provider.equals(health.provider)','model.equals(health.model)',
               'health.routeRateLimitsReady','EXPECTED_DEPLOYMENT_IDENTITY_NOT_BOUND']:
    assert phrase in capability_policy, phrase
capabilities=(APP/'app/src/main/java/com/kiraworld/sarahtravel/ProtectedBackendCapabilities.java').read_text(encoding='utf-8')
for phrase in ['tokenFingerprint(SarahModelConfig.backendToken())',
               'route_rate_limits_ready','conversationReady(Context context)',
               'FAILED_MAX_AGE_MS','isChecking()',
               'CACHE_CONTRACT = "authenticated-capabilities-v1"',
               'return base + "/capabilities"',
               '"Authorization", "Bearer " + SarahModelConfig.backendToken()',
               '"SarahMorganTravel/" + BuildConfig.VERSION_NAME',
               '.putBoolean("contract_verified", decision != null && decision.contractVerified)']:
    assert phrase in capabilities, phrase
assert 'return base + "/health"' not in capabilities
assert 'return SarahModelConfig.backendUrl().isEmpty()' in capabilities
capability_tests=(ROOT/'tests/ProtectedBackendCapabilityPolicyTest.java').read_text(encoding='utf-8')
assert 'HTTP 401/wrong-token response cannot create ready capability truth' in capability_tests
voice_config=(APP/'app/src/main/java/com/kiraworld/sarahtravel/ElevenLabsVoiceConfig.java').read_text(encoding='utf-8')
for phrase in ['backendToken().equals(SarahModelConfig.backendToken())',
               'protectedRoot(backendUrl()).equalsIgnoreCase',
               'protectedRoot(SarahModelConfig.backendUrl())']:
    assert phrase in voice_config, phrase
model_config=(APP/'app/src/main/java/com/kiraworld/sarahtravel/SarahModelConfig.java').read_text(encoding='utf-8')
assert 'ProtectedBackendCapabilities.conversationReady(context)' in model_config
assert 'return !backendUrl().isEmpty() || !openAiApiKey().isEmpty();' not in model_config
event_backup=(APP/'app/src/main/java/com/kiraworld/sarahtravel/EventTripPreUpgradeBackupGate.java').read_text(encoding='utf-8')
for phrase in ['readSqliteUserVersion','"-wal"','verifyExistingBackup',
               'expectedBytes','currentFiles.equals(seen)','sha256(current)',
               'UNEXPECTED_DATABASE_VERSION_',
               'R1_PRE_UPGRADE_BACKUP_HASH_VERIFIED','rollback_acceptance',
               'output.getFD().sync()','sha256(output)']:
    assert phrase in event_backup, phrase
application=(APP/'app/src/main/java/com/kiraworld/sarahtravel/SarahApplication.java').read_text(encoding='utf-8')
assert 'jobs.cancelAll()' in application and 'eventTripUpgradeState()' in application
assert 'repairEventMisclassification' not in application
assert 'writable.delete(' not in application
assert 'throw new IllegalStateException' not in application.split('eventTripUpgradeState = EventTripPreUpgradeBackupGate.ensure',1)[1].split('ProtectedBackendCapabilities.refreshAsync',1)[0]
for phrase in ['showEventTripUpgradeBlocked','Sarah protected your existing travel data',
               'No event-trip migration ran']:
    assert phrase in main, phrase
version_policy=(APP/'app/src/main/java/com/kiraworld/sarahtravel/EventTripPreUpgradeVersionPolicy.java').read_text(encoding='utf-8')
for phrase in ['existingVersion == 1','existingVersion == 2','existingVersion < 1','existingVersion > 2']:
    assert phrase in version_policy, phrase
settings_layout=(APP/'app/src/main/res/layout/activity_settings.xml').read_text(encoding='utf-8')
assert 'Sarah Travel OS 2.5' not in settings_layout
main_layout=(APP/'app/src/main/res/layout/activity_main.xml').read_text(encoding='utf-8')
assert '@+id/bottomControls' in main_layout and 'android:minHeight="48dp"' in main_layout
sponsor=(APP/'app/src/main/java/com/kiraworld/sarahtravel/SponsorConnectionsActivity.java').read_text(encoding='utf-8')
for phrase in ['Use my current location','locationCoordinator.resolve','SOURCE_DEVICE_RESOLVED',
               'Connect Gmail read-only','GmailAuthorizationActivity.class',
               'Review Gmail connection and receipts','Gmail not connected · monitoring off']:
    assert phrase in sponsor, phrase
for activity_name in ['TravelNotebookActivity.java','SponsorConnectionsActivity.java','TrustedSyncActivity.java','TravelExplorerActivity.java','HackathonDemoActivity.java']:
    activity=(APP/'app/src/main/java/com/kiraworld/sarahtravel'/activity_name).read_text(encoding='utf-8')
    assert 'SafeAreaInsets.apply' in activity, activity_name

print('STATIC_PACKAGE_VALIDATION_PASS_R3')
