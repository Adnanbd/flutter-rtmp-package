# 0021 — Android: own USB permission flow, not libuvc `USBMonitor.register()`

- **Status:** Accepted
- **Date:** 2026-09-30

## Context
A host app reported "Permission denied" for a USB HDMI switcher (MT-VIKI MT-L4UHD, UVC + UAC over USB-C) with no
system USB dialog. Decompiling `libuvc-3.2.0.aar` showed:
- `USBMonitor.register()` calls `PendingIntent.getBroadcast(ctx, 0, intent, 0)`. Flags 0 throw
  `IllegalArgumentException` on targetSdk 31+. Its `registerReceiver(receiver, filter)` has no export flag, which
  also throws on targetSdk 34+.
- `UsbDeviceRegistry.register()` swallowed the exception, so the monitor stayed unregistered.
- `USBMonitor.requestPermission()` on an unregistered monitor logs "not registered?" and calls `onCancel` → `false`.
- A webcam appeared to work only because it already had USB permission (early `true` return).
- `onDetach` never fired, so no `usbDetached` events were sent.

## Decision
- `UsbDeviceRegistry` requests permission itself: `UsbManager.requestPermission` with a `PendingIntent` that is
  `FLAG_MUTABLE` on API 31+ (the system adds `EXTRA_DEVICE` / `EXTRA_PERMISSION_GRANTED`) and explicit
  (`setPackage`), as Android 14 forbids mutable implicit PendingIntents.
- One receiver for the permission action and `ACTION_USB_DEVICE_DETACHED`, `RECEIVER_NOT_EXPORTED` on API 33+.
- `USBMonitor` is kept only for `openDevice()` (needs USB permission only) and `destroy()`; `register()` is never called.
- Failures log `USB_REGISTER_FAILED` / `USB_PERMISSION_REQUEST_FAILED` via `DiagLogger` (log codes only; the wire
  contract is unchanged: `requestUsbPermission` still returns a bool).

## Consequences
- USB permission dialog works on Android 12+; `usbDetached` events are delivered again.
- Not device-verified as of 2026-09-30.
- On a libuvc upgrade, re-check whether `USBMonitor.register()` is fixed before going back to it.
