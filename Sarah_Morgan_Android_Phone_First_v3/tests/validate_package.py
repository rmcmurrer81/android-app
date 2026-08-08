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
for phrase in ['RecognizerIntent','ACTION_PICK_IMAGES','MemoryExtractor.extract','CloudVoiceClient.speak','showCalmMenu','startTriviaGame','connectedReplyWithRetry','ensureApproximateAreaForTurn','finalTurnRoute','ReplyTruthGuard.enforce','VoiceRoutePolicy.shouldAttemptPremium','connected.hasVerifiedWebReceipt()','TextTurnReceipt.build','findViewById(R.id.bottomNavigation)','actualProvider = connected.provider','actualModel = connected.model','BOUNDED_LOCAL_PLANNING_DRAFT','lower.contains("cheapest")','TravelSearchQueryPolicy.build','ownerSourceDetails','tap for source details','turnId','OfflineTuringPolicy.answer','PublicOnlineFallback.answerResult','sourceBackedEvent.turnRoute()','connectedRouteProven = true','if (changed) connectedRouteProven = false','Last reply:','Next:']:
    assert phrase in main, phrase
assert 'if (connected) lastSmartCallFailed = false' not in main

onboarding=(APP/'app/src/main/java/com/kiraworld/sarahtravel/OnboardingActivity.java').read_text(encoding='utf-8')
assert 'Age, birth year, or skip' in onboarding
assert 'Phone voice ready as fallback' in onboarding
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
assert 'I’m with you. We can stay with this subject.' not in demo
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
assert "versionName '2.5-r2-owner-repair'" in build
assert "applicationId 'com.kiraworld.sarahtravel'" in build
assert 'com.kiraworld.sarahtravel.r2candidate' not in build
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
assert 'if (!connected.hasVerifiedWebReceipt()) return false;' in events
assert '!connected.hasSourceUrl(sourceUrl)' in events
planner=(APP/'app/src/main/java/com/kiraworld/sarahtravel/AgenticTravelPlanner.java').read_text(encoding='utf-8')
assert 'TravelPlanningConversationPolicy.asksForCurrentCost' in planner
assert 'TravelPlanningConversationPolicy.shortTripReply' in planner
for phrase in ['TravelPlanningConversationPolicy.explicitlyPlansTrip','SAVE_PLANNED_TRIP','memoryConsent','conversation-confirmed planned destination; dates not set']:
    assert phrase in planner, phrase
backend_worker=(REPO/'services/sarah-model-proxy/src/index.js').read_text(encoding='utf-8')
assert 'annotation.type === "url_citation"' in backend_worker
assert 'item.status === "completed"' in backend_worker
sponsors=(APP/'app/src/main/java/com/kiraworld/sarahtravel/SponsorConnectionsActivity.java').read_text(encoding='utf-8')
assert 'Gmail mailbox access is not connected' in sponsors
assert 'BookingImportActivity.class' in sponsors
migrator=(APP/'app/src/main/java/com/kiraworld/sarahtravel/OwnerProfileDataMigrator.java').read_text(encoding='utf-8')
for store in ['TripPlanStore','StayRequestStore','TravelerNeedsStore','LoyaltyVaultStore','RoadTripProfileStore','HotelSearchState','SarahLocationStore','ProactiveDiscoveryStore','ProactiveResearchReceiptStore','VoiceReceiptStore']:
    assert store in migrator, store
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
for phrase in ['Intent.ACTION_OPEN_DOCUMENT','application/pdf','pending review','clearBookingImports','Connect Gmail (setup required)','getCanonicalFile()','count = in.read(buffer)) != -1','target.delete()',
               'BookingImportTextPolicy.accepted', 'Review shared booking text',
               'Nothing has been saved or scheduled',
               'launchedFromShare && !externalShareReviewed']:
    assert phrase in booking_import, phrase
booking_text_policy=(APP/'app/src/main/java/com/kiraworld/sarahtravel/BookingImportTextPolicy.java').read_text(encoding='utf-8')
for phrase in ['MAX_CHARS = 16_384', 'MAX_UTF8_BYTES = 32_768',
               'StandardCharsets.UTF_8', 'clean.length() <= MAX_CHARS']:
    assert phrase in booking_text_policy, phrase
gmail=(APP/'app/src/main/java/com/kiraworld/sarahtravel/GmailTravelConnection.java').read_text(encoding='utf-8')
assert 'implementationAvailable() { return false; }' in gmail
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
for phrase in ['url.pathname === "/search"','runProtectedSearch','current_source_not_configured','body.search_query']:
    assert phrase in worker, phrase
workflow=(REPO/'.github/workflows/sarah-2.5-online-judge-build.yml').read_text(encoding='utf-8')
for phrase in ['TAVILY_API_KEY: ${{ secrets.SARAH_TAVILY_API_KEY }}','Run R2 source and pure-policy acceptance','CURRENT_SOURCE_ACCEPTANCE_BLOCKED','SARAH_TAVILY_API_KEY: \'\'','sarah-contextual-chat-request.json','CANDIDATE_PENDING_OWNER_ACCEPTANCE','physical_galaxy_a17_acceptance','full_16_gate_owner_acceptance','physical_8gb_laptop_acceptance','media3_progressive_one_shot_post','protected_voice_route_receipt_required','first_network_byte','player_ready','response_complete','pending_physical_galaxy_a17_measurement','sarah-r2-${{ github.run_id }}-${{ github.run_attempt }}','id: signing_cache','steps.signing_cache.outputs.cache-hit','SARAH_R1_SIGNING_CERT_SHA256','SARAH_R1_APK_SHA256','apksigner verify --print-certs','BuildConfig.java','build/r2-apk-secret-scan','r1_signer_sha256','r2_signer_sha256','same-package-in-place-only']:
    assert phrase in workflow, phrase
assert 'strings Sarah-Morgan-2.5-R2-OWNER-ACCEPTANCE-CANDIDATE.apk' not in workflow
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
for phrase in ['booking_imports/','sarah_event_trips.db','sarah_profile_vault.xml','sarah_voice_receipts.xml']:
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
for phrase in ['BuildConfig.SARAH_BUILD_COMMIT','local/unbound build','TrustedDeviceStore.hasPeers','ElevenLabs Sarah voice ready']:
    assert phrase in settings_source, phrase
for phrase in ['KnowledgePackSchedulingPolicy.settingsCanEnable',
               'KnowledgePackSchedulingPolicy.persistEnabled',
               'autoResearch.setChecked(false)', 'autoResearch.setEnabled(canEnable)',
               'BackgroundResearchPolicy.DEFAULT_BACKGROUND_MONITORING_ENABLED',
               '.putBoolean("deal_alerts_enabled", monitoringEnabled)']:
    assert phrase in settings_source, phrase
onboarding_source=(APP/'app/src/main/java/com/kiraworld/sarahtravel/OnboardingActivity.java').read_text(encoding='utf-8')
assert 'ElevenLabs Sarah voice will be tried online' in onboarding_source
assert 'Robert' not in onboarding_source and 'Newark' not in onboarding_source
hackathon_demo=(APP/'app/src/main/java/com/kiraworld/sarahtravel/HackathonDemoActivity.java').read_text(encoding='utf-8')
assert 'Robert' not in hackathon_demo
travel_hub=(APP/'app/src/main/java/com/kiraworld/sarahtravel/TravelHubActivity.java').read_text(encoding='utf-8')
assert 'TravelUi.makeSectionsCollapsible(root)' in travel_hub and '"Add dates"' in travel_hub
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
online_workflow=(REPO/'.github/workflows/sarah-2.5-online-judge-build.yml').read_text(encoding='utf-8')
for dependency in ['KnownEventCatalog.java','GenericEventReference.java']:
    assert dependency in online_workflow, dependency

lan_policy=(APP/'app/src/main/java/com/kiraworld/sarahtravel/TrustedLanEndpointPolicy.java').read_text(encoding='utf-8')
for phrase in ['PORT = 8769','bytes[0] == 10','bytes[0] == 127','bytes[0] == 169 && bytes[1] == 254','bytes[0] == 192 && bytes[1] == 168','uniqueLocal','linkLocal']:
    assert phrase in lan_policy, phrase
trusted_sync=(APP/'app/src/main/java/com/kiraworld/sarahtravel/TrustedSyncClient.java').read_text(encoding='utf-8')
for phrase in ['TrustedLanEndpointPolicy.requireLocalHost','TrustedSyncProtocol.encrypt','TrustedSyncProtocol.signature','X-Sarah-Device-Token','MAX_RESPONSE_BYTES','isTransportAccepted() { return false; }','requireAcceptedTransport()','Device sync is disabled']:
    assert phrase in trusted_sync, phrase
trusted_sync_activity=(APP/'app/src/main/java/com/kiraworld/sarahtravel/TrustedSyncActivity.java').read_text(encoding='utf-8')
for phrase in ['network sync is disabled','scan.setEnabled(false)','request.setEnabled(false)','manualPair.setEnabled(false)','selected.setEnabled(false)','all.setEnabled(false)']:
    assert phrase in trusted_sync_activity, phrase

all_android_java='\n'.join(
    path.read_text(encoding='utf-8')
    for path in (APP/'app/src/main/java').rglob('*.java'))
for unsupported in ['List.of(', 'List.copyOf(', 'Set.of(',
                    'toString(StandardCharsets.UTF_8)']:
    assert unsupported not in all_android_java, unsupported
assert re.search(
        r'getBoolean\(\s*"deal_alerts_enabled"\s*,\s*true\s*\)',
        all_android_java) is None
for activity_name in ['TravelNotebookActivity.java','SponsorConnectionsActivity.java','TrustedSyncActivity.java','TravelExplorerActivity.java','HackathonDemoActivity.java']:
    activity=(APP/'app/src/main/java/com/kiraworld/sarahtravel'/activity_name).read_text(encoding='utf-8')
    assert 'SafeAreaInsets.apply' in activity, activity_name

print('STATIC_PACKAGE_VALIDATION_PASS_R2')
