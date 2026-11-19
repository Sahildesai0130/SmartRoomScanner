# Smart Room Scanner

AR-based room measurement and 2D floor plan generation for Android.
Final-year project — Group CP36 (Pranay Kumar, Sahil Desai, Saipreet Naik, Ayushi Miistry). Guide: Mrs. Natasha D'Costa.

## Get the app on your phone

### Option A — GitHub builds the APK for you (no Android Studio needed)
1. Create a new **public or private repository** on GitHub.
2. Upload everything in this folder to it (on the repo page: *Add file → Upload files*, drag the folder contents in, *Commit*).
   Make sure the hidden `.github` folder is included — if your file picker hides it, use GitHub Desktop or `git push`.
3. Open the **Actions** tab. The *Build APK* workflow starts automatically (≈ 6–8 minutes).
4. When it is green, open **Releases** (right side of the repo page) on your phone and download `SmartRoomScanner.apk`.
5. Open the file and allow *Install unknown apps* for your browser / file manager when Android asks.

### Option B — Android Studio
1. *File → Open* this folder (Android Studio Ladybug or newer). Let Gradle sync.
2. Turn on *Developer options → USB debugging* on the phone, connect it, press **Run ▶**.

### Requirements
- Android 7.0+ and an [ARCore-supported phone](https://developers.google.com/ar/devices) for AR scanning.
  The app asks to install *Google Play Services for AR* if needed.
- Phones without ARCore can still use **Draw manually**.

## How to scan a room
1. **New scan** → point at the floor and move the phone slowly until the pointer appears.
2. Aim the centre pointer at the floor **where two walls meet** and tap **Add corner**.
   Green = on the detected floor (best), amber = estimated, red = no floor. Go around the room in order.
3. **Close room** after the last corner.
4. Aim at the **ceiling straight above any corner** and tap **Capture** (or type the height).
5. Optional: choose Door/Window, aim at the floor below each edge and tap **Mark edge** twice.
6. **Finish** → the plan is refined (PCA), squared (least squares) and opened in the editor.
7. In the editor: drag corners, tap a wall to set its length, add doors/windows, **Verify with tape**,
   undo/redo, and **Export** (top-right) to DXF / PNG / PDF / JSON.

Tips: good lighting, textured surfaces, and walk slowly. Use *Verify with tape* on the longest wall for best accuracy.

## Architecture (MVVM + Clean Architecture, Hilt)

```
app/src/main/java/com/cp36/roomscanner/
├── domain/                 Pure Kotlin – no Android code (unit tested)
│   ├── model/              RoomPlan, Opening, Vec2, units & formatting
│   └── geometry/           Geometry.kt  – Shoelace, PCA line fitting, least-squares orthogonal snapping
│                           PlanOps.kt   – editing operations (immutable → undo/redo)
├── data/                   Room database (projects), SharedPreferences settings, repositories
├── di/                     Hilt module
├── ar/                     ARCore: BackgroundRenderer (OpenGL ES camera feed), ArRenderer
│                           (SLAM tracking, plane detection, hit testing, anchors, wall feature points)
├── export/                 DXF (R12, mm), PNG, PDF report, JSON
└── ui/                     Jetpack Compose screens + ViewModels
    ├── home/  scan/  editor/  settings/  plan/ (shared floor-plan drawing)  common/  theme/
```

## Algorithms and where they are in the code

| Algorithm | File | What it does |
|---|---|---|
| Visual-inertial SLAM (ARCore) | `ar/ArRenderer.kt` | `session.update()` gives the phone pose in metres |
| Plane detection + ray casting | `ar/ArRenderer.kt` | `frame.hitTest()` at the screen centre; fallback ray ∩ floor plane |
| PCA line fitting | `Geometry.fitLinePca`, `refineWithWallPoints` | Fits wall lines through ARCore feature points, intersects them for corners |
| Least-squares orthogonal snapping | `Geometry.squareCorners` | Exact 90° corners with minimum total corner movement |
| Shoelace formula | `Geometry.area` | Floor area from corner coordinates |
| Ceiling height | `ScanViewModel.ceilingPreview` | Camera ray vs. vertical line above a corner |

Not included in this build (planned / future scope): OpenCV Canny + Hough snapping and the DeepLabV3 TensorFlow Lite
segmentation model, YOLO door detection, multi-room merging.

## Tests
`./gradlew testDebugUnitTest` runs `GeometryTest` (Shoelace, angles, PCA, snapping, wall refinement, unit parsing).
