# Sarah 1.1 Agentic Runtime Contract

This marker documents the test boundary for the feature branch.

The Android build must compile these connected pieces together:

- AgenticTravelPlanner and AgenticActionExecutor
- AgenticTravelCore and DestinationPackResponder
- TravelAutomation and SarahDatabase
- DestinationKnowledgeCoordinator
- DealWatchScheduler and DealWatchWorker
- TravelDealGateway and TravelDealResult
- DealNotificationManager
- SettingsActivity, MainActivity, AndroidManifest, and settings layout

Required user-visible behavior:

1. A destination planning statement queues a destination knowledge pack without a long questionnaire.
2. A dream destination or deal request creates a broad watch using reversible defaults.
3. "That is it" stops follow-up questions.
4. "I don't care" after fare context means flexible dates.
5. Actual fare notifications require a configured backend.
6. Weather is labeled forecast, climate, or unknown rather than overstated.
