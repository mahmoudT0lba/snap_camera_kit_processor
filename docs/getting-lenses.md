# How lenses are fetched from Snapchat (Camera Kit)

This document explains, end to end, how the app gets the list of lenses
("filters") it shows in the picker. The short version:

> **Snap is the source of truth.** There is *no* backend lens catalog. Whatever
> you publish to a **Lens Group** in the Snap developer portal shows up
> automatically, enumerated live on-device from Camera Kit.

The backend's only job is to hand the app the **credentials** (API token) and
the **list of Lens Group IDs** to enumerate. The lens *contents* come straight
from Snap.

---

## The two halves

| Concept | Comes from | Used for |
|---|---|---|
| **API token** | Snap developer portal → backend `prepareLive` credentials | Authenticating the Camera Kit session. One token per app (no iOS/Android split). |
| **Lens Group ID(s)** | Snap portal → backend (admin-managed categories) | Each group = one category chip in the picker. |
| **Lenses inside a group** | **Snap cloud, live, on-device** | The actual filter tiles. Never stored on our backend. |

A **Lens Group** is just Snap's container/folder for lenses, identified by a
group ID string from the portal. The backend sends one or more group IDs; the
app enumerates each group's lenses directly from Snap.

---

## End-to-end flow

```
Backend prepareLive creds
  (apiToken, snapDefaultGroupId, snapLensGroups[])
          │
          ▼
LivekitBeauty.initializeSnapForSession()          ← lib/core/livestream/.../beauty.part.dart
  • SnapCameraKitController.instance.sessionGroups = lensGroups
  • setRenderResolution(...)  (pre-init)
  • SnapCameraKitController.initialize(apiToken, defaultGroupId)
          │
          ▼
Native plugin: create Snap Session + warm the default group's cache
  (Android prefetchGroup / iOS session setup)
          │
          ▼
ref.invalidate(snapLensCatalogProvider)           ← on session init AND on every picker open
          │
          ▼
snapLensCatalog provider                          ← lib/features/beauty_filters/.../snap_lens_catalog_service.dart
  for each group in sessionGroups (sorted):
      SnapCameraKitController.getGroupLenses(groupId: group.groupId)
          │
          ▼
MethodChannel 'getGroupLenses'  ──────────────►  Native enumeration of the group
          │                                         Android: repository.get(QueryCriteria.Available)
          │                                         iOS:     repository.addObserver(groupID:) (one-shot)
          ▼
List<Map<String,String?>>  →  _mapLenses()  →  List<SnapLensInfo>  →  SnapLensCatalog
          │
          ▼
SnapBeautyBottomSheet → _LensesTab → lens tiles
```

### 1. Backend → credentials
The backend's `prepareLive` response carries the Snap credentials: the API
token, an optional default group id (`snapDefaultGroupId`), and the
admin-managed `snapLensGroups` (each `{groupId, name, sortOrder}`). These are
read in [`stream_view.dart`](../../../lib/features/live/presentation/widgets/shared/stream_view.dart)
when the streamer's preview starts.

### 2. Host app → initialize the session
[`LivekitBeauty.initializeSnapForSession()`](../../../lib/core/livestream/services/livekit_stream_service/beauty.part.dart)
does three things, in order:

1. **Publishes the groups** to the plugin:
   `SnapCameraKitController.instance.sessionGroups = lensGroups;`
   This is what the catalog provider later iterates — one chip per group.
2. **Sets render resolution** *before* init (the native side queues it).
3. **Calls `initialize(apiToken, defaultGroupId)`** — idempotent, so it's safe
   to call again on reconnect.

`defaultGroupId` is the "warm-up" group the session loads at startup; if the
backend didn't send one, the first entry of `lensGroups` is used.

### 3. Native → create session and warm the cache
`initialize` builds the Camera Kit `Session` natively. On Android the default
group is pre-fetched (`prefetchGroup` → `repository.get(Available)`), so by the
time the picker enumerates, the lens list for that group is already populated.

### 4. Provider → enumerate every group
[`snapLensCatalog`](../../../lib/features/beauty_filters/services/remote_services/snap_lens_catalog_service.dart)
is the Riverpod provider that powers the picker:

- It reads `SnapCameraKitController.instance.sessionGroups`.
- **If groups are configured:** one category per group, in `sortOrder`. For each
  it calls `getGroupLenses(groupId: group.groupId)` and tags every lens with its
  owning category.
- **If `sessionGroups` is empty** (old backend / no categories): it falls back to
  `getGroupLenses()` with no group id, enumerating the single initialized default
  group as "uncategorized" lenses.
- **If Snap isn't initialized yet:** the native call returns an empty list, so the
  picker shows its empty state and a retry re-enumerates once the session is live.

### 5. The MethodChannel call
[`getGroupLenses`](../lib/src/snap_camera_kit_controller.dart) is the single
entry point. It invokes the native `'getGroupLenses'` method and normalizes the
result into a list of maps:

```dart
final raw = await SnapCameraKitController.instance.getGroupLenses(groupId: 'GROUP_ID');
// → [ {lensId, groupId, name?, iconUrl?}, ... ]
```

Each map has:

| Key | Notes |
|---|---|
| `lensId` | Lens UUID from the portal / Lens Studio publish. Required. |
| `groupId` | The owning Lens Group id. Required. |
| `name` | Optional. When absent, the app falls back to `lensId` as the lookup key. |
| `iconUrl` | Optional thumbnail URL (Snap-hosted). |

Lenses with a missing `lensId`/`groupId` are dropped.

### 6. Native enumeration (platform specifics)
Both platforms read from Snap's **lens repository** for the requested group and
return whatever lenses are currently available there:

- **Android** ([`SnapCameraKitController.kt`](../android/src/main/kotlin/net/vibezo/snap_camera_kit_processor/SnapCameraKitController.kt)):
  one-shot `session.lenses.repository.get(QueryCriteria.Available(setOf(groupId)))`.
- **iOS** ([`SnapCameraKitController.swift`](../ios/Classes/SnapCameraKitController.swift)):
  `session.lenses.repository.addObserver(observer, groupID:)`; the observer fires
  with the group's lenses, completes the call, then removes itself — effectively
  one-shot.

`Available` = the lenses Snap has already fetched metadata for in that group.
The init-time warm-up exists precisely so this set is complete by the time the
user opens the picker.

### 7. Mapping → catalog → UI
`_mapLenses()` (in the catalog service) turns each native map into a
`SnapLensInfo` (assigning a stable synthetic `id`, the category tag, and a
`name`/`displayName`). The result is a `SnapLensCatalog` of categories +
lenses, which [`SnapBeautyBottomSheet`](../../../lib/features/beauty_filters/presentation/widgets/snap_beauty_bottom_sheet.dart)
renders as filter chips + lens tiles.

---

## Applying a lens (for completeness)

The picker doesn't pre-download binaries. When the user taps a tile,
`applyLens(lensId, groupId)` is called; **Snap fetches the lens binary from its
own cloud at apply time.** The first apply for a lens may incur a brief network
fetch; later applies are local-cache hits. `clearLens()` returns to the raw
camera.

---

## Freshness — making sure the list is always current

The catalog provider is `keepAlive`, so once built it holds its snapshot for the
session. It's deliberately busted so freshly-published lenses always appear:

1. **On session init** — after `initialize()` succeeds, so the picker shows the
   admin-managed groups instead of a stale/fallback enumeration.
2. **On every picker open** — the entry points
   ([`go_live_page.dart`](../../../lib/features/live/presentation/pages/go_live_page.dart),
   [`smart_live_helpers.dart`](../../../lib/features/live/presentation/widgets/smart/smart_live_helpers.dart))
   call `ref.invalidate(snapLensCatalogProvider)` right before opening the sheet.
3. **Manual refresh button** — the picker header has a refresh icon that busts
   the provider on demand.

> Note: `getGroupLenses` itself does **no** Dart-side caching — it hits the
> native repository on every call. The only session-level cache is the
> `keepAlive` provider above. (`hasGpuAcceleration()` *is* cached, but that's a
> device-capability flag, not lens data.)

---

## Requirements & gotchas

- **GPU tier:** the feature is hidden on devices that fail
  `hasGpuAcceleration()` — Snap's software fallback is too slow for live.
- **Not initialized → empty list:** `getGroupLenses` returns `[]` (not an error)
  until the session is initialized. Retry after init.
- **No backend catalog:** don't add lens IDs to our backend expecting them to
  show — publish them to the Lens Group in the Snap portal instead.
- **Optional backend override:** `SnapLensCatalogService` (the HTTP path hitting
  `ApiEndpoints.snapLenses`) exists for future backend-controlled ordering /
  localized names but is **not used by default** — the live on-device
  enumeration above is the active path.

---

## Key files

| File | Role |
|---|---|
| [`lib/src/snap_camera_kit_controller.dart`](../lib/src/snap_camera_kit_controller.dart) | Dart MethodChannel surface — `getGroupLenses`, `applyLens`, `sessionGroups`. |
| [`android/.../SnapCameraKitController.kt`](../android/src/main/kotlin/net/vibezo/snap_camera_kit_processor/SnapCameraKitController.kt) | Android native enumeration (`repository.get`). |
| [`ios/Classes/SnapCameraKitController.swift`](../ios/Classes/SnapCameraKitController.swift) | iOS native enumeration (`repository.addObserver`). |
| `lib/features/beauty_filters/.../snap_lens_catalog_service.dart` | `snapLensCatalog` provider — builds the catalog from `sessionGroups`. |
| `lib/core/livestream/.../beauty.part.dart` | `initializeSnapForSession` — sets `sessionGroups`, calls `initialize`. |
| `lib/features/beauty_filters/.../snap_beauty_bottom_sheet.dart` | The picker UI + manual refresh button. |
