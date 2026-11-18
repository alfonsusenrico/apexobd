# obd-protocol Specification

## Purpose
Support multiple physical OBD2 adapter connection mechanisms (`Wi-Fi TCP`, `Bluetooth Classic SPP`, `Bluetooth LE GATT`, and `USB Serial OTG`) cleanly behind the `ObdTransport` interface.

## ADDED Requirements

### Requirement: Selectable OBD2 Transport Mechanisms (`Wi-Fi`, `Bluetooth Classic`, `BLE`, `USB OTG`)
The application SHALL support selecting `ObdConnectionType` across `WIFI_TCP`, `BLUETOOTH_CLASSIC`, `BLUETOOTH_LE`, and `USB_SERIAL` behind a unified `ObdTransport` factory, defaulting existing configurations safely to `WIFI_TCP` (`192.168.0.10:35000`).

#### Scenario: Switching connection mechanism between Wi-Fi, Bluetooth Classic, BLE, and USB OTG
- **WHEN** the user selects `Bluetooth Classic (SPP)`, `Bluetooth LE (BLE)`, `USB-Serial (OTG)`, or `Wi-Fi (TCP/IP)` in `AdapterConnectionSheet`
- **THEN** the selected mechanism is persisted cleanly, instantiates the matching `ObdTransport` implementation for live HUD polling and ECU scans, and displays the active mechanism label in the Home header pill
