# logging-tracking Specification Delta

## ADDED Requirements

### Requirement: Fresh-Open Vehicle Picker and True Process Exit on Background Service Stop
When 2 or more vehicle profiles exist on a fresh cold start (with no paused run active), the app SHALL present a 1-tap vehicle picker before connecting to the adapter, and terminating the background listener service SHALL checkpoint any active run, flush vehicle stats, remove the app task, and terminate the process cleanly.

#### Scenario: User stops background service and reopens app later
- **WHEN** the user taps the ongoing foreground notification (or Stop Background Listener) while 2 vehicles are configured
- **THEN** the app checkpoints any active run, flushes stats, stops `LoggingForegroundService`, finishes and removes its task, and kills the process so the next launch is a cold start presenting the Vehicle Picker
