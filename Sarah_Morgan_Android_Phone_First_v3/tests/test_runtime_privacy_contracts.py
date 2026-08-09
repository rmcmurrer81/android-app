"""Static release gates for cancellation, profile, network, and voice leases.

Physical Android lifecycle tests still run in CI/device acceptance. These
checks keep the exact source boundaries from silently regressing first.
"""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "android-app/app/src/main/java/com/kiraworld/sarahtravel"


def source(name: str) -> str:
    return (JAVA / name).read_text(encoding="utf-8")


class RuntimePrivacyContractsTest(unittest.TestCase):
    def test_external_images_use_one_fsynced_immutable_private_snapshot(self):
        snapshot = source("PrivateContentSnapshot.java")
        sanitizer = source("ImageSanitizer.java")
        booking = source("BookingImportActivity.java")
        main = source("MainActivity.java")

        self.assertEqual(snapshot.count("resolver.openInputStream(uri)"), 1)
        for phrase in (
            "target.createNewFile()",
            "output.getFD().sync()",
            "target.setReadOnly()",
            "target.canWrite()",
            "getCanonicalFile()",
            "maximumBytes",
            "target.length() != total",
            "!target.delete() || target.exists()",
        ):
            self.assertIn(phrase, snapshot)

        for forbidden in (
            "ContentResolver",
            "android.net.Uri",
            "openInputStream",
            "decodeStream",
        ):
            self.assertNotIn(forbidden, sanitizer)
        for phrase in (
            "File snapshot,\n            File directory,\n            String approvedResolverMimeType",
            "Files.readAllBytes(exactSnapshot.toPath())",
            "BitmapFactory.decodeByteArray(source",
            "exactSnapshot.canWrite()",
            "bounds.outMimeType",
            "approvedMime.equals(decodedMime)",
            "MAX_SOURCE_PIXELS",
            "MAX_DECODE_PIXELS",
            "out.getFD().sync()",
            "!complete && file.exists()",
        ):
            self.assertIn(phrase, sanitizer)
        sampled_gate = sanitizer.index("sampledWidth * sampledHeight > MAX_DECODE_PIXELS")
        full_decode = sanitizer.index(
            "Bitmap bitmap = BitmapFactory.decodeByteArray(source"
        )
        self.assertLess(sampled_gate, full_decode)

        screenshot = booking[
            booking.index("private void importScreenshot("):
            booking.index("private void importPdf(")
        ]
        self.assertNotIn("openInputStream", screenshot)
        self.assertNotIn("verifyBoundedSource", screenshot)
        self.assertEqual(screenshot.count("PrivateContentSnapshot.capture("), 1)
        self.assertIn("approvedResolverMimeType))", screenshot)
        self.assertIn("snapshot.approvedMimeType()", screenshot)
        self.assertLess(
            screenshot.index("requireConfirmedImportLease(ownerConfirmed)"),
            screenshot.index("PrivateContentSnapshot.capture("),
        )
        photo = main[main.index("} else if (requestCode == REQ_PHOTO) {"):]
        self.assertEqual(photo.count("getContentResolver().getType(uri)"), 1)
        self.assertIn("final String approvedPhotoMime", photo)
        self.assertLess(
            photo.index("getContentResolver().getType(uri)"),
            photo.index("PrivateContentSnapshot.capture("),
        )
        self.assertIn('"chat_photo",\n                            approvedPhotoMime))', photo)
        self.assertIn("snapshot.approvedMimeType()", photo)
        self.assertIn("final ImageSanitizer.Result[] prepared", photo)
        self.assertLess(
            photo.index("prepared[0] = result;"),
            photo.index("} catch (Exception e)"),
        )
        for phrase in (
            "cleanupSanitizedDerivative(",
            "A private residual requires owner cleanup at",
            "Log.e(TAG, detail, e)",
            "if (!exactFile.delete() || exactFile.exists())",
            "return exactFile.getCanonicalPath()",
        ):
            self.assertIn(phrase, main)

    def test_deal_watch_requires_exact_active_confirmed_owner(self):
        main = source("MainActivity.java")
        worker = source("DealWatchWorker.java")
        lease = source("ConfirmedOwnerLease.java")
        self.assertIn("boolean activeConfirmedOwner", main)
        self.assertIn("boolean dealMonitoringAllowed = activeConfirmedOwner", main)
        self.assertIn(
            "ConfirmedOwnerLease ownerLease = ConfirmedOwnerLease.capture(context)",
            worker,
        )
        gate = worker.index("if (ownerLease == null) return false;")
        self.assertLess(gate, worker.index("db.listActiveDealWatches(100)"))
        self.assertLess(gate, worker.index("TravelDealGateway.check("))
        self.assertIn("context, watch, ownerLease", worker)
        self.assertIn("MobilityWatchCoordinator.run(context, ownerLease)", worker)
        for phrase in (
            "people.getActiveProfile()",
            "people.uniqueConfirmedOwnerCandidate()",
            '"yes".equals(active.getOrDefault("active", "no"))',
            '"yes".equals(active.getOrDefault("is_owner", "no"))',
            "ProfileMigrationPolicy.isConfirmedDisplayName(",
            "Thread.currentThread().isInterrupted()",
        ):
            self.assertIn(phrase, lease)

    def test_owner_global_agentic_writes_reject_guest_before_any_branch(self):
        executor = source("AgenticActionExecutor.java")
        policy = source("AgenticGlobalActionPolicy.java")
        owner_global_types = (
            "SAVE_WISH",
            "CREATE_DEAL_WATCH",
            "UPDATE_DESTINATION_FOCUS",
            "SET_FLEXIBLE_DATES",
            "SAVE_JOURNEY_PLAN",
            "CREATE_MOBILITY_WATCH",
        )
        for action_type in owner_global_types:
            self.assertIn(f"AgenticTravelPlanner.{action_type}.equals(actionType)", policy)

        loop = executor.index("for (AgenticTravelPlanner.Action action : actions)")
        gate = executor.index(
            "AgenticGlobalActionPolicy.requiresExactConfirmedOwner(action.type)",
            loop,
        )
        first_branch = executor.index(
            "if (AgenticTravelPlanner.QUEUE_KNOWLEDGE_PACK.equals(action.type))",
            gate,
        )
        self.assertLess(gate, first_branch)
        gate_body = executor[gate:first_branch]
        for phrase in (
            "!isExactConfirmedOwner(ownerLease, profile)",
            "failedForegroundReceipts.add(",
            "AgenticGlobalActionPolicy.rejectedReceipt(",
            "continue;",
        ):
            self.assertIn(phrase, gate_body)
        self.assertIn(
            "ConfirmedOwnerLease ownerLease = ConfirmedOwnerLease.capture(context)",
            executor,
        )
        self.assertIn("ownerLease.requireActive()", executor)
        self.assertIn("profileId.equals(ownerLease.personId())", executor)
        self.assertIn('"yes".equals(profile.getOrDefault("active_speaker_is_owner", "no"))', executor)
        self.assertNotIn("private static boolean isOwner(", executor)
        self.assertGreaterEqual(
            executor.count("isExactConfirmedOwner(ownerLease, profile)"), 5
        )
        self.assertIn("No global travel data changed", policy)

    def test_event_scheduler_controls_require_exact_confirmed_owner(self):
        executor = source("AgenticActionExecutor.java")
        booking = source("BookingImportActivity.java")

        scheduler_boundary = executor.index(
            "boolean exactOwnerAtSchedulerBoundary ="
        )
        scheduler_body = executor[scheduler_boundary:executor.index(
            "return new Result(", scheduler_boundary
        )]
        for phrase in (
            "exactOwnerAtSchedulerBoundary\n                && eventMonitorCancellationApplied",
            "eventMonitorNeedsScheduling && exactOwnerAtSchedulerBoundary",
            "importedBooking && exactOwnerAtSchedulerBoundary",
            "EventMonitorScheduler.cancelPeriodicMonitoring(context)",
            "EventMonitorScheduler.ensureScheduled(context)",
            "EventMonitorScheduler.runSoon(context)",
            "exact confirmed owner lease changed before scheduling",
        ):
            self.assertIn(phrase, scheduler_body)
        self.assertIn(
            "eventMonitorCancellationApplied =\n                                isExactConfirmedOwner(ownerLease, profile)",
            executor,
        )
        self.assertIn(
            "alertsEnabled && sourceRouteAvailable\n                                && isExactConfirmedOwner(ownerLease, profile)",
            executor,
        )

        self.assertEqual(booking.count("queueExactOwnerEventRefresh();"), 3)
        self.assertEqual(booking.count("EventMonitorScheduler.runSoon(this)"), 1)
        helper = booking[booking.index("private boolean queueExactOwnerEventRefresh()"):
                         booking.index("private void requireConfirmedImportLease")]
        for phrase in (
            "ConfirmedOwnerLease.capture(this)",
            "boundPersonId.equals(ownerLease.personId())",
            "ownerLease.requireActive()",
            "return EventMonitorScheduler.runSoon(this)",
        ):
            self.assertIn(phrase, helper)

    def test_global_watch_scheduler_rechecks_owner_at_final_boundary(self):
        executor = source("AgenticActionExecutor.java")
        start = executor.index("boolean monitoringRunnable =")
        end = executor.index("boolean eventSchedulerAccepted = false;", start)
        boundary = executor[start:end]
        for phrase in (
            "boolean exactOwnerAtSchedulerBoundary =",
            "isExactConfirmedOwner(ownerLease, profile)",
            "monitoringRunnable && exactOwnerAtSchedulerBoundary",
            "DealWatchScheduler.ensureScheduled(context)",
            "DealWatchScheduler.runSoon(context)",
            "globalWatchSchedulerAccepted = periodicAccepted",
            "durableReceipts.addAll(newGlobalWatchReceipts)",
            "exact confirmed owner lease changed before scheduling",
        ):
            self.assertIn(phrase, boundary)
        self.assertLess(
            boundary.index("monitoringRunnable && exactOwnerAtSchedulerBoundary"),
            boundary.index("DealWatchScheduler.ensureScheduled(context)"),
        )
        self.assertIn(
            "globalWatchSchedulerAccepted || eventSchedulerAccepted",
            executor,
        )

    def test_knowledge_pack_scheduler_rechecks_owner_after_promotion(self):
        executor = source("AgenticActionExecutor.java")
        branch = executor[
            executor.index("if (AgenticTravelPlanner.QUEUE_KNOWLEDGE_PACK.equals(action.type))"):
            executor.index("} else if (AgenticTravelPlanner.SAVE_WISH.equals(action.type))")
        ]
        gate = branch.index(
            "promotedBeforeScheduling\n                            && isExactConfirmedOwner(ownerLease, profile)"
        )
        ensure = branch.index("DealWatchScheduler.ensureScheduled(context)")
        self.assertLess(gate, ensure)
        for phrase in (
            "ownerLeaseRevokedBeforeScheduling = true",
            "db.markKnowledgePackNotScheduled(",
            "exact confirmed owner lease changed before scheduling",
        ):
            self.assertIn(phrase, branch)

    def test_deal_and_mobility_connections_are_lease_bound_and_cancellable(self):
        worker = source("DealWatchWorker.java")
        for cancel in (
            "TravelDealGateway.cancel(run.thread)",
            "MobilityGateway.cancel(run.thread)",
        ):
            self.assertIn(cancel, worker)

        for name in ("TravelDealGateway.java", "MobilityGateway.java"):
            gateway = source(name)
            register = gateway.index(
                "ACTIVE_CONNECTIONS.putIfAbsent(worker, connection)"
            )
            first_after_register = gateway.index("lease.requireActive()", register)
            output = gateway.index("connection.getOutputStream()", register)
            write = gateway.index("out.write(body)", output)
            flush = gateway.index("out.flush()", write)
            response = gateway.index("connection.getResponseCode()", flush)
            read = gateway.index("reader.readLine()", response)

            self.assertIn(
                "ConcurrentHashMap<Thread, HttpURLConnection> ACTIVE_CONNECTIONS",
                gateway,
            )
            self.assertIn("public static void cancel(Thread worker)", gateway)
            self.assertIn("ACTIVE_CONNECTIONS.remove(worker)", gateway)
            self.assertIn("setInstanceFollowRedirects(false)", gateway)
            self.assertLess(register, first_after_register)
            self.assertLess(first_after_register, output)
            self.assertIn("lease.requireActive()", gateway[output:write])
            self.assertIn("lease.requireActive()", gateway[write:flush])
            self.assertIn("lease.requireActive()", gateway[flush:response])
            self.assertIn("lease.requireActive()", gateway[response:read])
            self.assertIn("lease.requireActive()", gateway[read:])
            self.assertIn("ACTIVE_CONNECTIONS.remove(worker, connection)", gateway)
            self.assertIn("connection.disconnect()", gateway)

    def test_mobility_coordinator_rechecks_lease_before_visible_writes(self):
        coordinator = source("MobilityWatchCoordinator.java")
        self.assertIn(
            "run(Context context, ConfirmedOwnerLease ownerLease)", coordinator
        )
        self.assertIn("context, watch, ownerLease", coordinator)
        self.assertGreaterEqual(coordinator.count("ownerLease.requireActive()"), 8)
        for marker in ("store.updateWatch(", "MobilityNotificationManager.post("):
            positions = [
                index for index in range(len(coordinator))
                if coordinator.startswith(marker, index)
            ]
            self.assertTrue(positions, marker)
            for position in positions:
                self.assertIn(
                    "ownerLease.requireActive()",
                    coordinator[max(0, position - 180):position],
                    f"{marker} at {position}",
                )
        for marker in ("store.listActiveWatches(100)", "MobilityGateway.check("):
            position = coordinator.index(marker)
            self.assertIn(
                "ownerLease.requireActive()",
                coordinator[max(0, position - 180):position],
                marker,
            )
        self.assertIn("if (!ownerLease.isActive()) return false;", coordinator)

    def test_deal_worker_rechecks_lease_before_updates_and_notifications(self):
        worker = source("DealWatchWorker.java")
        knowledge = source("DestinationKnowledgeCoordinator.java")
        for marker in ("db.updateDealWatchCheck(", "DealNotificationManager.post("):
            positions = [
                index for index in range(len(worker))
                if worker.startswith(marker, index)
            ]
            self.assertTrue(positions, marker)
            for position in positions:
                self.assertIn(
                    "ownerLease.requireActive()",
                    worker[max(0, position - 200):position],
                    f"{marker} at {position}",
                )
        for marker in (
            "db.listActiveDealWatches(100)",
            "TravelDealGateway.check(",
            "MobilityWatchCoordinator.run(",
        ):
            position = worker.index(marker)
            self.assertIn(
                "ownerLease.requireActive()",
                worker[max(0, position - 200):position],
                marker,
            )
        self.assertIn('db.updateDealWatchCheck(id, "temporary_error"', worker)
        error = worker.index('db.updateDealWatchCheck(id, "temporary_error"')
        self.assertIn("ownerLease.requireActive()", worker[error - 160:error])
        self.assertIn(
            "BackgroundResearchPolicy.MAX_PACKS_PER_RUN,\n                            ownerLease",
            worker,
        )
        self.assertIn("ConfirmedOwnerLease ownerLease", knowledge)
        self.assertIn("CONFIRMED_OWNER_KNOWLEDGE_KEY_MISMATCH", knowledge)
        for marker in (
            "TavilyClient.search(",
            "ConnectedModelGateway.respondDetailed(",
            "db.upsertKnowledgePack(",
            "db.recordKnowledgeAttempt(",
        ):
            positions = [
                index for index in range(len(knowledge))
                if knowledge.startswith(marker, index)
            ]
            self.assertTrue(positions, marker)
            for position in positions:
                self.assertIn(
                    "requireActive(ownerLease)",
                    knowledge[max(0, position - 200):position],
                    f"{marker} at {position}",
                )

    def test_profile_switch_revokes_background_and_voice_work(self):
        main = source("MainActivity.java")
        profile_button = source("ProfileButton.java")
        body = main.split("private boolean invalidatePriorSpeakerWork()", 1)[1].split(
            "boolean prepareForProfileSwitch()", 1
        )[0]
        for phrase in (
            "voiceRequestSequence.incrementAndGet()",
            "CloudVoiceClient.cancel()",
            "tts.stop()",
            "DealWatchScheduler.cancel(this)",
            "EventMonitorScheduler.cancel(this)",
            "ProactiveDiscoveryScheduler.cancel(this)",
            "pauseBackgroundResearchForOwnerTurn()",
            "LegacyEventTripOwnerClaimGate.ensure(this, owner, active)",
        ):
            self.assertIn(phrase, body)
        self.assertIn("return invalidatePriorSpeakerWork();", main)
        revoke = profile_button.index("prepareForProfileSwitch()")
        switch = profile_button.index("store.setActiveByName(name)")
        recreate = profile_button.index("((Activity) context).recreate()")
        self.assertLess(revoke, switch)
        self.assertLess(switch, recreate)

        foreground = main.split("private void refreshKnowledgeAsync()", 1)[1].split(
            "private void scheduleDeferredKnowledgeRefresh()", 1
        )[0]
        self.assertIn("ConfirmedOwnerLease.capture(", foreground)
        self.assertIn("FOREGROUND_RESEARCH_CONFIRMED_OWNER_LEASE_REQUIRED", foreground)
        self.assertIn("BackgroundResearchPolicy.MAX_PACKS_PER_RUN,\n                        ownerLease", foreground)
        self.assertIn("ownerLease.requireActive()", foreground)

        cancellation = main.split(
            "private void pauseBackgroundResearchForOwnerTurn()", 1
        )[1].split("private Map<String, String> currentProfile()", 1)[0]
        for phrase in (
            "TavilyClient.cancel(worker)",
            "ConnectedModelGateway.cancel(worker)",
            "worker.interrupt()",
        ):
            self.assertIn(phrase, cancellation)
        destroy = main.split("protected void onDestroy()", 1)[1]
        self.assertIn("pauseBackgroundResearchForOwnerTurn()", destroy)

    def test_event_job_has_exact_token_and_cancels_every_network_route(self):
        job = source("EventMonitorJobService.java")
        scheduler = source("EventMonitorScheduler.java")
        event = source("EventResearchCoordinator.java")
        booking = source("BookingExtractionCoordinator.java")
        self.assertIn("EXTRA_SCHEDULE_TOKEN", scheduler)
        self.assertEqual(scheduler.count("extras.putLong(EXTRA_SCHEDULE_TOKEN"), 2)
        self.assertIn("pending.getExtras().getLong(", scheduler)
        self.assertIn("EXTRA_SCHEDULE_TOKEN, 0L) == 0L", job)
        self.assertIn("run.scheduleToken != stoppingToken", job)
        self.assertIn("activeRun != null", job)
        self.assertIn("if (mayFinish) jobFinished(run.params, retry)", job)
        self.assertIn("ConfirmedOwnerLease.capture(", job)
        self.assertIn("ownerLease.requireActive()", job)
        self.assertIn("ownerLease);", job)
        self.assertGreaterEqual(
            event.count("leaseStillValid(context, store, ownerLease)"), 8
        )
        self.assertIn("ownerLease.requireActive()", event)
        self.assertGreaterEqual(
            booking.count("leaseStillValid(store, ownerLease)"), 6
        )
        self.assertIn("ownerLease.requireActive()", booking)
        for phrase in (
            "run.thread.interrupt()",
            "ConnectedModelGateway.cancel(run.thread)",
            "TavilyClient.cancel(run.thread)",
            "OfficialEventPageLookup.cancel(run.thread)",
        ):
            self.assertIn(phrase, job)

    def test_proactive_job_has_exact_token_owner_lease_and_boundary_rechecks(self):
        job = source("ProactiveDiscoveryJobService.java")
        scheduler = source("ProactiveDiscoveryScheduler.java")
        coordinator = source("ProactiveDiscoveryCoordinator.java")

        self.assertEqual(scheduler.count("extras.putLong(EXTRA_SCHEDULE_TOKEN"), 2)
        self.assertIn("pending.getExtras().getLong(", scheduler)
        self.assertIn("EXTRA_SCHEDULE_TOKEN, 0L) == 0L", job)
        for phrase in (
            "if (activeRun != null) return false;",
            "activeRun == run",
            "run.params.getJobId() != params.getJobId()",
            "run.scheduleToken == 0L",
            "run.scheduleToken != stoppingToken",
            "ConfirmedOwnerLease.capture(",
            "ownerLease.requireActive()",
            "TavilyClient.cancel(run.thread)",
            "ConnectedModelGateway.cancel(run.thread)",
        ):
            self.assertIn(phrase, job)
        self.assertGreaterEqual(
            coordinator.count("requireActiveLease(ownerLease, personId)"), 8
        )
        self.assertIn("() -> !activeLease(ownerLease, personId)", coordinator)
        failed = coordinator.index("ProactiveResearchReceiptStore.failed(")
        self.assertIn(
            "if (activeLease(ownerLease, personId))",
            coordinator[failed - 180:failed],
        )

    def test_ui_global_writes_and_schedulers_recheck_exact_owner(self):
        lease = source("ConfirmedOwnerLease.java")
        main = source("MainActivity.java")
        settings = source("SettingsActivity.java")
        notebook = source("TravelNotebookActivity.java")

        for phrase in (
            "public static boolean isExactActiveOwner(",
            "ConfirmedOwnerLease lease = capture(context)",
            "expected.equals(lease.personId())",
            "lease.requireActive()",
        ):
            self.assertIn(phrase, lease)
        startup = main[main.index("boolean dealMonitoringAllowed ="):
                       main.index("setContentView(R.layout.activity_main)")]
        self.assertEqual(startup.count("ConfirmedOwnerLease.isExactActiveOwner("), 3)
        proactive = main[main.index("private void applyProactiveResearchSchedule("):
                          main.index("private List<Map<String, String>> currentDealWatches(")]
        self.assertEqual(proactive.count("ConfirmedOwnerLease.isExactActiveOwner("), 2)
        self.assertLess(
            proactive.rindex("ConfirmedOwnerLease.isExactActiveOwner("),
            proactive.index("ProactiveDiscoveryScheduler.ensureScheduled(this)"),
        )

        save = settings[settings.index("save.setOnClickListener(v -> {"):
                        settings.index("private void finishWithMessage()")]
        owner_gate = save.index("!ConfirmedOwnerLease.isExactActiveOwner(this, activePersonId)")
        self.assertLess(owner_gate, save.index("setConversationMode(this, selectedMode)"))
        for scheduler_call in (
            "DealWatchScheduler.ensureScheduled(this)",
            "DealWatchScheduler.runSoon(this)",
            "EventMonitorScheduler.ensureScheduled(this)",
            "EventMonitorScheduler.runSoon(this)",
            "ProactiveDiscoveryScheduler.ensureScheduled(this)",
            "ProactiveDiscoveryScheduler.runSoon(this)",
        ):
            position = save.index(scheduler_call)
            self.assertIn(
                "ConfirmedOwnerLease.isExactActiveOwner(this, activePersonId)",
                save[max(0, position - 180):position],
                scheduler_call,
            )

        self.assertIn("private boolean isExactOwnerActiveNow()", notebook)
        for write in ("db.addWish(", "db.addTrip(", "db.queueKnowledgePack("):
            positions = [
                index for index in range(len(notebook))
                if notebook.startswith(write, index)
            ]
            self.assertTrue(positions, write)
            for position in positions:
                self.assertIn(
                    "isExactOwnerActiveNow()",
                    notebook[max(0, position - 260):position],
                    write,
                )
        for position in (
            index for index in range(len(notebook))
            if notebook.startswith("DealWatchScheduler.runSoon(this)", index)
        ):
            self.assertIn("isExactOwnerActiveNow()", notebook[position - 100:position])

    def test_android_tts_reply_ids_are_monotonic_not_wall_clock_based(self):
        tts = source("SarahTts.java")
        self.assertIn("AtomicLong replyUtteranceSequence", tts)
        self.assertIn("replyUtteranceSequence.incrementAndGet()", tts)
        self.assertNotIn(
            'String utteranceId = "sarah_reply_" + System.currentTimeMillis()',
            tts,
        )

    def test_deal_job_has_generation_token_and_one_exact_active_run(self):
        job = source("DealWatchWorker.java")
        scheduler = source("DealWatchScheduler.java")

        self.assertEqual(scheduler.count("extras.putLong(EXTRA_SCHEDULE_TOKEN"), 2)
        self.assertIn("NEXT_TOKEN.incrementAndGet()", scheduler)
        self.assertIn("EXTRA_SCHEDULE_TOKEN, 0L) == 0L", job)
        self.assertIn("if (activeRun != null) return false;", job)
        self.assertIn("activeRun = run;", job)
        self.assertIn("activeRun == run", job)
        self.assertIn("run.params.getJobId() != params.getJobId()", job)
        self.assertIn("run.scheduleToken != stoppingToken", job)
        self.assertIn("if (mayFinish) jobFinished(run.params, retry);", job)
        self.assertNotIn("run.params != params", job)
        for phrase in (
            "run.thread.interrupt()",
            "TravelDealGateway.cancel(run.thread)",
            "MobilityGateway.cancel(run.thread)",
            "TavilyClient.cancel(run.thread)",
            "ConnectedModelGateway.cancel(run.thread)",
            "OfficialEventPageLookup.cancel(run.thread)",
        ):
            self.assertIn(phrase, job)

    def test_connected_clients_close_cancel_before_registration_race(self):
        for name in (
            "SarahBackendClient.java",
            "OpenAIClient.java",
            "OfficialEventPageLookup.java",
        ):
            client = source(name)
            register = client.index("ACTIVE_CONNECTIONS.put(worker, connection)")
            after_register = client.index("requireActive(worker)", register)
            self.assertGreater(after_register, register, name)
            self.assertIn("worker.isInterrupted()", client, name)
            self.assertIn("ACTIVE_CONNECTIONS.remove(worker, connection)", client, name)

    def test_voice_supersession_and_tts_init_failure_are_observable(self):
        router = source("SarahVoiceRouter.java")
        tts = source("SarahTts.java")
        self.assertIn("requestSequence.incrementAndGet()", router)
        self.assertIn("CloudVoiceClient.cancel()", router)
        self.assertIn("local.stop()", router)
        self.assertGreaterEqual(
            router.count("request != requestSequence.get()"), 2
        )
        self.assertIn('"android_tts_initialization_failed"', tts)
        self.assertIn("pendingSpeechListener = null", tts)

    def test_cloud_voice_rechecks_generation_before_publication_and_playback(self):
        cloud = source("CloudVoiceClient.java")
        start = cloud.index("private static void startStreaming(")
        body = cloud[start:cloud.index("private static StreamingRequest", start)]
        construct = body.index("session = new PlaybackSession(")
        prepublish = body.index(
            "if (REQUEST_SEQUENCE.get() != requestGeneration)", construct
        )
        publish = body.index("ACTIVE_SESSION.getAndSet(session)", prepublish)
        set_source = body.index("player.setMediaSource(mediaSource)", publish)
        prepare = body.index("player.prepare()", set_source)
        play = body.index("player.play()", prepare)

        self.assertLess(construct, prepublish)
        self.assertLess(prepublish, publish)
        self.assertNotIn("player.addListener", body[prepublish:publish])
        self.assertEqual(body.count("releaseIfStale(requestGeneration, session)"), 3)
        self.assertLess(
            body.index("releaseIfStale(requestGeneration, session)", publish),
            set_source,
        )
        self.assertLess(
            body.index("releaseIfStale(requestGeneration, session)", set_source),
            prepare,
        )
        self.assertLess(
            body.index("releaseIfStale(requestGeneration, session)", prepare),
            play,
        )
        self.assertIn("ACTIVE_SESSION.get() == session", body)
        self.assertIn('session.fail("superseded_before_playback")', body)

    def test_notebook_does_not_call_every_saved_event_monitored(self):
        notebook = source("TravelNotebookActivity.java")
        self.assertIn('addHeader("Event trips")', notebook)
        self.assertNotIn('addHeader("Monitored event trips")', notebook)
        for phrase in (
            'event.getOrDefault("monitor_enabled", "no")',
            "ownerMonitoringOptIn",
            "EventMonitorScheduler.isDurablyScheduled(this)",
            "current source route unavailable",
            "Saved event trip - background monitoring is off",
        ):
            self.assertIn(phrase, notebook)

    def test_connected_turn_uses_one_useful_read_and_only_a_bounded_transient_retry(self):
        policy = source("ConnectedTurnPolicy.java")
        backend = source("SarahBackendClient.java")
        openai = source("OpenAIClient.java")
        gateway = source("ConnectedModelGateway.java")
        main = source("MainActivity.java")

        for phrase in (
            "READ_TIMEOUT_MS = 11_500",
            "MAX_NETWORK_WAIT_MS = 15_000",
            "MIN_SECOND_ATTEMPT_BUDGET_MS = 4_000",
            "isRetryableFailure(failure)",
            "status == 404",
            "status == 408",
            "status == 429",
            "status >= 500",
            "current instanceof SSLHandshakeException",
        ):
            self.assertIn(phrase, policy)

        for client in (backend, openai):
            for phrase in (
                "ConnectedTurnPolicy.connectTimeoutMs(remainingBudgetMs)",
                "ConnectedTurnPolicy.readTimeoutMs(remainingBudgetMs)",
                'setRequestProperty("Cache-Control", "no-cache")',
                'setRequestProperty("Pragma", "no-cache")',
                "new ConnectedTurnPolicy.HttpStatusException(",
            ):
                self.assertIn(phrase, client)

        for phrase in (
            "ConnectedTurnPolicy.endpointForAttempt(\n                safeEndpoint, attemptNumber)",
        ):
            self.assertIn(phrase, backend)
        for phrase in (
            "static String endpointForAttempt(String endpoint, int attemptNumber)",
            "UUID.randomUUID().toString()",
            '"sarah_attempt=" + Math.max(1, attemptNumber)',
            '"sarah_nonce=" + encodeQueryPart(exactNonce)',
            'exact.indexOf(\'#\')',
            '"sarah_attempt".equals(decodedKey)',
            '"sarah_nonce".equals(decodedKey)',
        ):
            self.assertIn(phrase, policy)

        self.assertIn("int attemptNumber,\n            long remainingBudgetMs", gateway)
        self.assertIn("attemptNumber,\n                    remainingBudgetMs", gateway)
        for phrase in (
            "final long deadlineNanos = ConnectedTurnPolicy.deadlineNanos(System.nanoTime());",
            "final int attemptNumber = attempt;",
            "long socketBudgetMs = ConnectedTurnPolicy.remainingUntilDeadlineMs(",
            "web, searchQuery, image, attemptNumber, socketBudgetMs",
            "long futureWaitBudgetMs = ConnectedTurnPolicy.remainingUntilDeadlineMs(",
            "futureWaitBudgetMs, TimeUnit.MILLISECONDS",
            "Connected reply completed after its shared deadline",
            "attempt, remainingAfterFailure, lastFailure",
        ):
            self.assertIn(phrase, main)
        submit = main.index("networkAttemptExecutor.submit")
        socket_budget = main.index("long socketBudgetMs", submit)
        network_call = main.index("ConnectedModelGateway.respondDetailed(", socket_budget)
        future_wait = main.index("long futureWaitBudgetMs", network_call)
        future_get = main.index("attemptFuture.get(", future_wait)
        post_get_deadline = main.index(
            "if (ConnectedTurnPolicy.remainingUntilDeadlineMs(", future_get
        )
        online_receipt = main.index("if (!connected.online)", post_get_deadline)
        accepted_return = main.index("return connected;", online_receipt)
        self.assertLess(submit, socket_budget)
        self.assertLess(socket_budget, network_call)
        self.assertLess(network_call, future_wait)
        self.assertLess(future_wait, future_get)
        self.assertLess(future_get, post_get_deadline)
        self.assertLess(post_get_deadline, online_receipt)
        self.assertLess(online_receipt, accepted_return)

    def test_startup_never_globally_deletes_misclassified_event_rows(self):
        application = source("SarahApplication.java")
        self.assertNotIn("repairEventMisclassification", application)
        self.assertNotIn("writable.delete(", application)


if __name__ == "__main__":
    unittest.main()
