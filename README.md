# KML Explorer

An open-source Android application for visualizing, editing, and creating KML track logs. KML Explorer provides an interactive way to view track paths on an OpenStreetMap, edit individual track nodes, update track metadata, and import directions shared directly from Google Maps with real-world elevation profiling.

> **Note**: This application contains no default sample or mock data. To get started, you must either upload a `.kml` file, paste a raw KML XML string under the **File Details** tab, or import a shared Google Maps directions URL.

## Application Architecture & Visual Mockup

```
┌────────────────────────────────────────────────────────┐
│                      KML EXPLORER                      │
├────────────────────────────────────────────────────────┤
│  [ LIVE VIEW ]          [ ELEVATION ]      [ FILE ]    │
├────────────────────────────────────────────────────────┤
│                                                        │
│   (M)  ▲                                               │
│       / \   Map Track Line:  🗺️                         │
│      /   \  [---○-------○-------○---]                  │
│     /     \                                            │
│                                                        │
├────────────────────────────────────────────────────────┤
│  STATUS / EVENTS LOG                                   │
│  Track: No KML loaded. Upload, paste XML, or import.   │
├────────────────────────────────────────────────────────┤
│   [📂 Open file]       [🔗 Import G-Maps Link]         │
└────────────────────────────────────────────────────────┘
```

---

## Features

- **Interactive Map View**: Displays KML paths and waypoints directly using `osmdroid` for responsive and offline-capable tile rendering.
- **Node Editing & Drag-and-Drop**: Select precise route coordinates on the map view and live-modify or drag nodes to dynamically modify route paths.
- **Google Maps Directions Import**: 
  1. Generate any driving or walking route on Google Maps.
  2. Copy the directions link or share it directly to KML Explorer.
  3. The application parses the directions, pulls elevation profiles via `OpenTopoData` (using the SRTM 90m dataset), and instantly wraps those coordinate points into a fully-functional, valid, and editable KML route document.
- **Structured Error & System Event Logs**: Full-scale application logging captures file imports, geocoding actions, OSRM routing payloads, and elevation synchronization.
- **Source Code XML Editor**: Preview, edit, write, or copy raw KML XML text.
- **Metadata Management**: Edit track descriptions, custom labels, and start datetimes directly through the material menu.

---

## Technical Architecture

- **Jetpack Compose**: 100% Kotlin-based responsive layouts paired with the **Material 3 Design System**.
- **Osmdroid**: Native map view integration with custom overlaid Polylines and Marker managers.
- **Project OSRM API**: Resolves waypoint pairs derived from shared directions into standard geometric coordinates.
- **OpenTopoData API**: Synchronizes coordinates with elevation profiles via the SRTM90m API to provide exact vertical profiles.
- **Coroutines & stateful StateFlows**: Clean asynchronous task flow for long-lived network requests.

---

## CI/CD Workflow

This repository includes a pre-configured **GitHub Actions Workflow** located at `.github/workflows/android-build.yml` that:
1. Runs lint checks and unit compilation tasks.
2. Automatically compiles the final Android Application Package (`app-debug.apk`).
3. Re-labels and deploys the release asset under the clean custom release name: `kml-explorer-v1.0.<run_number>.apk`.

---

## Local Build Instructions

To build and compile KML Explorer locally:

1. **Clone the repository**:
   ```bash
   git clone https://github.com/yourusername/kml-dashboard.git
   cd kml-dashboard
   ```

2. **Clean and compile using Gradle**:
   ```bash
   ./gradlew assembleDebug
   ```

3. **Locate your built APK**:
   ```
   app/build/outputs/apk/debug/kml-explorer-v1.0.<run_number>.apk
   ```

---

## License

This project is licensed under the [MIT License](LICENSE).
