# FloatWM

A minimalist floating-bubble multitasking launcher for stock, non-rooted
Android 16/17, built against the constraint **zero ADB, ever** — every
capability here comes from permissions grantable through on-device Settings
UI plus whatever the device's own Developer options screen exposes.

## The two tiers, and why they exist

The single hardest constraint on this whole project: **a normal,
non-rooted app cannot draw its own title bar around another app's live
window.** That would require embedding a foreign task inside a view we
control (the `TaskView`/`ActivityView` family of APIs), and those are gated
behind `INJECT_EVENTS` / `MANAGE_ACTIVITY_TASKS` — signature-level
permissions no sideloaded APK can hold, Developer options or not, ADB or
not (ADB *can't* grant signature permissions either; that's not a
"zero-ADB" gap, it's a hard OS wall for anyone without root).

So every third-party-app window in this app is one of two things, decided
at launch time by `AppLaunchController`, based on a live, public-API-only
capability check (`FreeformCapability.isFreeformSupported`, wrapping
`PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT`):

- **Tier A — real freeform** (only if the device currently reports the
  system feature above, i.e. the Developer option in step 4 is on and
  supported by this build): the app launches via
  `ActivityOptions.setLaunchBounds()` into a genuine OS-drawn floating
  window. It really is draggable and resizable. Its title bar, close
  button, and minimize button are drawn and owned by System UI, not by
  this app — we deliberately don't try to paint our own chrome on top of a
  window we don't control the bounds-tracking for. First time this
  happens in a session, the app shows a one-time toast saying exactly
  that, so the switch in behavior isn't a silent surprise.

- **Tier B — full-screen + control strip** (the fallback, and the common
  case on an untouched stock device): the app launches full-screen, and
  the service shows a small persistent, draggable pill (our own
  `TYPE_APPLICATION_OVERLAY` window) with that app's icon, name, a
  minimize button, and a close button. This is the closest achievable
  analog of a custom title bar for a window we don't own:
  - **Drag** the strip: repositions the strip itself, not the underlying
    full-screen activity (which has no "position" to move).
  - **Minimize**: sends the current foreground task to the background
    (`ACTION_MAIN`/`CATEGORY_HOME` — the same effect as pressing Home) and
    replaces the strip with a small bubble carrying that app's icon.
    Tapping the bubble re-issues the launch intent for that package, which
    Android reorders to the front on its own — no task-management
    permission needed for that half.
  - **Close**: same backgrounding action, but the bubble is discarded
    instead of kept. This does **not** stop the target app's process —
    no unprivileged app can force-stop another app's process; only the
    system (or root) can. What you get is exactly what pressing Home
    gives you: the task goes to the background and Android reclaims it
    under normal memory pressure, same as it would for any app.
  - **Resize**: not implemented, and there's nothing honest to implement —
    a full-screen activity has no bounds to drag.

## Wanted behavior → what actually happens

| Wanted | Status | Notes |
|---|---|---|
| Tap launcher icon → start service, show draggable bubble, no full-screen UI | ✅ Full | `MainActivity` is a transparent, momentary permission handshake; it never renders content. |
| Request permissions if denied | ✅ Full | Sends to `ACTION_MANAGE_OVERLAY_PERMISSION`; won't loop forever if refused — see `MainActivity`. |
| Tap bubble → floating, title-bar-draggable window listing all launchable apps | ✅ Full | This window is 100% ours; drag/resize/minimize/close are all real, not a fallback. |
| Tap an app icon → launch in a new floating draggable window | ✅ Tier A / ⚠️ Tier B | See tiers above. Tier B gives you a controllable strip, not a moved/resized *app* window. |
| Any app can have multiple windows, where possible | ⚠️ Partial | `FLAG_ACTIVITY_MULTIPLE_TASK` is applied on each fresh picker-tap launch. Two hard limits, both platform-imposed, not app choices: (1) apps declaring `singleTask`/`singleInstance` in their own manifest always collapse to one instance, regardless of our flags. (2) restoring a specific bubble re-issues that package's launch intent rather than targeting a specific task ID (a normal app cannot obtain another app's task ID — `getAppTasks()` only ever returns the caller's own tasks) — Android brings back *a* matching task, usually the most recent, not provably the exact one that bubble was minimized from. |
| Title bar with close / minimize-to-bubble / resize | ✅ (our windows) / ⚠️ (Tier B) / N/A (Tier A, system-owned) | See tiers above. |
| Closing the main bubble kills all services of the app | ✅ Full | Two independent routes to the same `closeEverything()`: dragging the main bubble onto the trash-drop zone, or tapping the persistent notification. Both tear down every window and call `stopForeground` + `stopSelf`. |
| Prefer built-in/default Android features | ✅ | `TYPE_APPLICATION_OVERLAY`, a `specialUse` foreground service, the `<queries>` package-visibility declaration (not `QUERY_ALL_PACKAGES`), `ActivityOptions.setLaunchBounds`, `FEATURE_FREEFORM_WINDOW_MANAGEMENT`, and ordinary task-reorder `Intent` semantics — no reflection, no hidden APIs, no shell commands. |


## Optional root tier (added for rooted builds, e.g. Magisk-rooted AxionOS)

Everything above is the original zero-ADB, non-root design and still works
exactly as documented on a stock, non-rooted device -- this section only
applies when `su` is actually available.

Google has, on some Android 16 builds, removed the Developer Options
toggle that flips the two `Settings.Global` flags backing
`FEATURE_FREEFORM_WINDOW_MANAGEMENT` (see the platform note in
`FreeformCapability`). A rooted device doesn't need that toggle to exist:
`RootFreeformBootstrap` runs once, ever, per install (tracked in its own
`SharedPreferences`, independent of anything else in the app) from
`OverlayService.onCreate()`:

1. Checks for `su` via `RootShell.isRootAvailable()`. No-op, silently, if
   it's not there -- every non-root code path is completely unaffected.
2. If root is available: `settings put global enable_freeform_support 1`
   and `settings put global force_resizable_activities 1`, then
   `killall com.android.systemui` so the flags take effect immediately
   (Android relaunches that persistent process on its own, same as if it
   had crashed) instead of requiring a full reboot.
3. Shows a one-time toast (`root_freeform_enabled_notice`) the moment this
   actually happens, so the brief status-bar/quick-settings flicker isn't
   a silent surprise.

After this runs, `FreeformCapability.isFreeformSupported()` should report
true on the *next* app launch attempt, and `AppLaunchController` starts
taking Tier A (real freeform) instead of Tier B for every subsequent
launch -- no further root involvement needed anywhere else in the app.

`RootShell` is a minimal, one-shot `su` wrapper (see its kdoc) -- deliberately
not a full library like libsu, since nothing else here needs a persistent
interactive root shell.

## Release signing

`.github/workflows/release.yml` builds a properly-signed release APK and
publishes it as a GitHub Release whenever you push a tag matching `v*`
(`git tag v1.0 && git push --tags`), or via "Run workflow" in the Actions
tab for a manual release.

A dedicated release keystore was generated for this project (2048-bit RSA,
alias `floatwm`, valid 10000 days) rather than using the debug keystore --
debug keystores are regenerated fresh on every ephemeral GitHub Actions
runner, so two debug-signed releases would never carry the same signature
and Android would refuse to treat the second as an update to the first.

One-time setup, in the repo's **Settings → Secrets and variables → Actions**,
add these four repository secrets:

| Secret name | Value |
|---|---|
| `FLOATWM_RELEASE_KEYSTORE_BASE64` | the `.keystore` file, base64-encoded (`base64 -w0 release.keystore`) |
| `FLOATWM_RELEASE_KEYSTORE_PASSWORD` | keystore password |
| `FLOATWM_RELEASE_KEY_ALIAS` | `floatwm` |
| `FLOATWM_RELEASE_KEY_PASSWORD` | key password (same as keystore password for this key) |

**Keep the `.keystore` file and its password out of the repo itself** --
only the base64 secret above should ever leave your machine. Losing this
keystore means every future release has to use a new one, and Android will
treat it as a different app for update purposes; back it up somewhere safe
outside git.

`app/build.gradle.kts`'s release `signingConfig` only activates when the
`FLOATWM_RELEASE_KEYSTORE_PATH` env var is set (which `release.yml` sets
from the secret above) -- a plain local `gradle assembleRelease` with no
env vars set produces an unsigned release APK, which is expected AGP
behavior, not a misconfiguration.

## Project structure

```
app/src/main/java/com/floatwm/launcher/
  MainActivity.kt            permission handshake, no UI
  OverlayService.kt          owns every overlay window + the whole lifecycle
  FloatApplication.kt
  core/
    AppLaunchController.kt   Tier A vs Tier B launch decision
    FreeformCapability.kt    public-API-only runtime capability check
    AppSession.kt            data model + the multi-instance/restore caveat
  ui/
    OverlayWindow.kt         WindowManager add/remove + drag + resize math
    AppGridAdapter.kt        RecyclerView adapter for the picker grid
  util/
    AppRepository.kt         queries PackageManager off the main thread
    Dp.kt
```

## Known gaps / things deliberately left as-is

- **No compile verification.** This was written and resource-cross-checked
  (every `R.id`/`R.string`/`R.drawable`/`R.style`/`findViewById<T>` call
  verified against what's actually declared) in an environment without the
  Android SDK or network access to Google's Maven, so it could not be run
  through an actual Gradle build. Open it in Android Studio first and
  expect at most small fixes, not a redesign.
- If overlay permission is revoked mid-session (user goes back into
  Settings and turns it off), `OverlayWindow.show()` catches the resulting
  `SecurityException` and logs instead of crashing, but nothing proactively
  detects the revocation — the bubble will just silently fail to redraw
  until the service restarts.
- No boot-start receiver — intentional. The spec says the *launcher icon*
  starts the service; auto-starting on boot would contradict that.
- Launcher icon uses a plain vector drawable rather than a full adaptive
  icon (foreground/background layers) — cosmetic, swap in your own
  `mipmap` set whenever convenient.
