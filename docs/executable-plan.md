# Windows Executable Plan — SCS2 Session Visualizer

This document specifies a multi-stage plan to produce a Windows executable
distribution of the SCS2 Session Visualizer JFX application using the standard
JDK tooling (`jpackage` and `jlink`). It follows the same project structure
and packaging philosophy as the existing Debian flow (`buildDebianPackage`
task in `scs2-session-visualizer-jfx/build.gradle.kts`).

## Decisions (locked in)

| Topic | Choice |
|---|---|
| Plan document | Single file `docs/executable-plan.md` |
| Deliverables | (a) Portable `app-image` folder, (b) `.msi` installer (WiX 3.x) |
| Launchers | Both `SCS2SessionVisualizer.exe` and `MCAPRepackApplication.exe` |
| Stage 1 status | Stage 1 (full JRE bundle) is a shipping artifact; Stage 2 (jlink-trimmed) is a size-reduction follow-up |
| Build integration | New Gradle tasks added to `scs2-session-visualizer-jfx/build.gradle.kts` (mirroring `buildDebianPackage`) |
| CI | Local-only in this plan; CI integration listed as a follow-up |
| Icon | Pre-generated `scs-icon.ico` committed alongside existing PNG/SVG |
| Installer version string | `17.0.32.1` (project version `17-0.32.1` with `-` → `.`) |
| Default heap | `-Xmx8g` |
| Code signing | Out of scope; listed as a follow-up |

## Stage overview

| Stage | Goal | Output |
|---|---|---|
| 0 | Prerequisites and one-time setup on the build machine | Tooling installed, icon committed |
| 1 | Build a Windows distribution with a bundled full JRE using `jpackage` | `app-image` directory + `.msi` installer (~300–400 MB) |
| 2 | Replace the bundled full JRE with a trimmed runtime built by `jlink` | Same artifacts, ~150–220 MB |
| 3 | Promote both stages into reproducible Gradle tasks | `packageWindowsAppImage`, `packageWindowsMsi`, `buildJlinkRuntime` |

Each stage is independently runnable. Stage 1 must work end-to-end before
Stage 2 is attempted.

---

## Stage 0 — Prerequisites (one-time)

### 0.1 Build-machine software

Install on the Windows build machine (any developer who will run the
packaging):

1. **JDK 17** (Temurin 17 LTS recommended). Must include `jpackage` and
   `jlink` on the `bin/` folder. Verify:
   ```bat
   java -version
   jpackage --version
   jlink --version
   ```
   Set `JAVA_HOME` to the JDK 17 install root.

2. **WiX Toolset 3.x** (3.11 or 3.14 — *not* 4.x; `jpackage` only supports
   the v3 series). Download from
   <https://github.com/wixtoolset/wix3/releases>. Install and add the
   install directory (`C:\Program Files (x86)\WiX Toolset v3.11\bin`) to
   `PATH`. Verify:
   ```bat
   candle.exe -?
   light.exe -?
   ```
   WiX is only required for `.msi` output; the portable `app-image` does
   not need it.

3. **JavaFX 17.0.8 jmods** (only required starting in Stage 2 — skip for
   Stage 1). Download
   `openjfx-17.0.8_windows-x64_bin-jmods.zip` from
   <https://gluonhq.com/products/javafx/> (matches the version pinned in
   `scs2-session-visualizer-jfx/build.gradle.kts`). Unzip somewhere
   stable, e.g. `C:\javafx-jmods-17.0.8`. Do **not** commit this to the
   repo — record the path as an environment variable (`JAVAFX_JMODS_DIR`)
   for Stage 2.

### 0.2 Icon artifact

`jpackage` on Windows requires a `.ico` file; the repo currently only
contains `scs2-session-visualizer-jfx/src/main/resources/icons/scs-icon.png`
and `scs-icon.svg`.

1. Convert `scs-icon.png` (or `scs-icon.svg`) to a multi-resolution
   `scs-icon.ico` containing the 16, 32, 48, 64, 128, and 256 pixel
   variants. One-off using ImageMagick:
   ```bat
   magick scs-icon.png -define icon:auto-resize=256,128,64,48,32,16 scs-icon.ico
   ```
   Or use an online converter; the only requirement is that it is a valid
   multi-resolution Windows `.ico`.

2. Commit the file at
   `scs2-session-visualizer-jfx/src/main/resources/icons/scs-icon.ico`
   alongside the existing `scs-icon.png` and `scs-icon.svg`. It is not
   loaded by JavaFX (which uses the PNG via `SCS_ICON_IMAGE` in
   `SessionVisualizerIOTools`), so committing it does not affect runtime
   behaviour.

### 0.3 Verify the inputs

Before continuing to Stage 1, run:

```bat
gradlew.bat :scs2-session-visualizer-jfx:installDist
```

This must produce
`scs2-session-visualizer-jfx\build\install\scs2-session-visualizer-jfx\`
containing `bin\` (with the existing `.bat` launchers) and `lib\` (with
all classpath jars). Confirm the main jar exists:

```
scs2-session-visualizer-jfx\build\install\scs2-session-visualizer-jfx\lib\scs2-session-visualizer-jfx-17-0.32.1.jar
```

If the filename differs (e.g. version bumped), use the actual name in the
`--main-jar` argument throughout the rest of this plan.

### 0.4 Reference values used throughout the plan

| Symbol | Value |
|---|---|
| Project version (Gradle) | `17-0.32.1` (from `group.gradle.properties`) |
| Installer version | `17.0.32.1` (Windows requires dotted-numeric only) |
| Vendor | `IHMC` |
| Application name | `SCS2SessionVisualizer` |
| Main class (GUI) | `us.ihmc.scs2.sessionVisualizer.jfx.SessionVisualizer` |
| Main class (MCAP) | `us.ihmc.scs2.sessionVisualizer.jfx.session.mcap.MCAPRepackApplication` |
| Main jar | `scs2-session-visualizer-jfx-17-0.32.1.jar` |
| Icon | `scs2-session-visualizer-jfx/src/main/resources/icons/scs-icon.ico` |
| `installDist` output | `scs2-session-visualizer-jfx/build/install/scs2-session-visualizer-jfx/` |
| Packaging output root | `scs2-session-visualizer-jfx/deployment/windows/` |

---

## Stage 1 — `jpackage` with bundled full JRE

Goal: produce a working Windows distribution using only `jpackage` and the
existing `installDist` output. The resulting app bundles a full JDK 17
runtime image; size is the trade-off for simplicity.

### 1.1 Strategy

The classpath jars in `build/install/scs2-session-visualizer-jfx/lib/` are
exactly what `jpackage` needs as `--input`. The JDK that runs `jpackage`
becomes the runtime image embedded in the bundle (this is the default
when `--runtime-image` is not specified). The two launchers
(`SCS2SessionVisualizer.exe` and `MCAPRepackApplication.exe`) are emitted
via the primary `--name` plus `--add-launcher`.

The GUI launcher should not show a console window (default behaviour).
The MCAP launcher *should* show a console window because
`MCAPRepackApplication` is a CLI tool that uses `commons-cli`, prints
help via `HelpFormatter`, writes progress to `stderr` via
`me.tongfei:progressbar`, and uses `System.exit(...)`. This is controlled
per-launcher via the `win-console=true` line in the add-launcher
properties file.

### 1.2 Inputs required

1. `installDist` has been run (Stage 0.3).
2. `scs-icon.ico` is committed (Stage 0.2).
3. `JAVA_HOME` points at a JDK 17.
4. WiX 3.x is on `PATH` (for `.msi` output).

### 1.3 Launcher properties file

Create the secondary launcher's property file under
`scs2-session-visualizer-jfx/deployment/windows/launchers/`:

**`MCAPRepackApplication.properties`**:

```properties
main-jar=scs2-session-visualizer-jfx-17-0.32.1.jar
main-class=us.ihmc.scs2.sessionVisualizer.jfx.session.mcap.MCAPRepackApplication
win-console=true
icon=scs-icon.ico
java-options=-Dprism.vsync=false
java-options=-Xmx2g
```

Notes:
- `win-console=true` attaches a console window so users see CLI help and
  progress bars.
- Heap is smaller here (`-Xmx2g`) because the MCAP tool does not need
  the GUI's allocation.
- The primary GUI launcher does not need its own properties file; its
  settings come from the top-level `jpackage` arguments.

### 1.4 `jpackage` invocations

All commands below are run from the repository root.

#### 1.4.1 Portable `app-image` (deliverable A)

```bat
jpackage ^
  --type app-image ^
  --name SCS2SessionVisualizer ^
  --app-version 17.0.32.1 ^
  --vendor "IHMC" ^
  --description "Simulation Construction Set 2 - Session Visualizer" ^
  --copyright "IHMC" ^
  --input  scs2-session-visualizer-jfx\build\install\scs2-session-visualizer-jfx\lib ^
  --dest   scs2-session-visualizer-jfx\deployment\windows\app-image ^
  --main-jar scs2-session-visualizer-jfx-17-0.32.1.jar ^
  --main-class us.ihmc.scs2.sessionVisualizer.jfx.SessionVisualizer ^
  --icon scs2-session-visualizer-jfx\src\main\resources\icons\scs-icon.ico ^
  --java-options "-Dprism.vsync=false" ^
  --java-options "-Xmx8g" ^
  --add-launcher MCAPRepackApplication=scs2-session-visualizer-jfx\deployment\windows\launchers\MCAPRepackApplication.properties
```

Result:
`scs2-session-visualizer-jfx\deployment\windows\app-image\SCS2SessionVisualizer\`
containing `SCS2SessionVisualizer.exe`, `MCAPRepackApplication.exe`,
`runtime\` (bundled JRE), and `app\` (the classpath jars). Distribute
as a zip.

#### 1.4.2 `.msi` installer (deliverable B)

```bat
jpackage ^
  --type msi ^
  --name SCS2SessionVisualizer ^
  --app-version 17.0.32.1 ^
  --vendor "IHMC" ^
  --description "Simulation Construction Set 2 - Session Visualizer" ^
  --copyright "IHMC" ^
  --input  scs2-session-visualizer-jfx\build\install\scs2-session-visualizer-jfx\lib ^
  --dest   scs2-session-visualizer-jfx\deployment\windows\msi ^
  --main-jar scs2-session-visualizer-jfx-17-0.32.1.jar ^
  --main-class us.ihmc.scs2.sessionVisualizer.jfx.SessionVisualizer ^
  --icon scs2-session-visualizer-jfx\src\main\resources\icons\scs-icon.ico ^
  --java-options "-Dprism.vsync=false" ^
  --java-options "-Xmx8g" ^
  --add-launcher MCAPRepackApplication=scs2-session-visualizer-jfx\deployment\windows\launchers\MCAPRepackApplication.properties ^
  --win-dir-chooser ^
  --win-menu ^
  --win-menu-group "SCS2" ^
  --win-shortcut ^
  --win-upgrade-uuid f74c546b-1d1d-4114-9812-809b8eb1412c
```

Result:
`scs2-session-visualizer-jfx\deployment\windows\msi\SCS2SessionVisualizer-17.0.32.1.msi`

Notes on the WiX-specific options:
- `--win-dir-chooser` lets the user pick install location (defaults
  otherwise to `C:\Program Files\SCS2SessionVisualizer\`).
- `--win-menu` + `--win-menu-group "SCS2"` creates Start Menu entries
  under an "SCS2" group.
- `--win-shortcut` adds a desktop shortcut.
- `--win-upgrade-uuid` is a **stable** UUID identifying this product
  across versions; future installers with the same UUID and a higher
  `--app-version` upgrade in place rather than install side-by-side.
  The product's canonical value is
  `f74c546b-1d1d-4114-9812-809b8eb1412c`. **Do not change it between
  releases.**

### 1.5 Stage 1 validation checklist

After running each invocation, perform these checks before declaring the
stage complete:

1. **Launch the GUI**: double-click
   `app-image\SCS2SessionVisualizer\SCS2SessionVisualizer.exe`. No console
   window should appear. The main window opens with the title
   "No Active Session" and the SCS icon in the title bar.
2. **Open a session**: load a small known-good session (e.g. a recorded
   MCAP log or a YoVariable log directory). Verify the YoVariable
   sidebar populates and the 3D view renders.
3. **Test the YoGraphic editor**: open the YoGraphic property editor
   and confirm the type dropdown is populated. This exercises the
   `org.reflections` runtime scan in `YoGraphicFXControllerTools` against
   the packaged classpath — if it returns an empty list, packaging is
   wrong.
4. **Test the MCAP launcher** from `cmd.exe`:
   ```bat
   "%LOCALAPPDATA%\SCS2SessionVisualizer\MCAPRepackApplication.exe" --help
   ```
   The help text from `HelpFormatter` should print to the attached
   console. Without `--help`, the file chooser should open.
5. **Install the MSI**: double-click the produced `.msi`. Verify Start
   Menu entries are created under "SCS2", desktop shortcut works, and
   the app launches from each entry.
6. **Uninstall**: confirm uninstall via Settings → Apps removes all
   files except user data under `%USERPROFILE%\.ihmc\`.
7. **Reinstall over upgrade**: bump `--app-version` to a higher number,
   keep the same `--win-upgrade-uuid`, and install again. The previous
   version should be replaced, not installed alongside.

### 1.6 Stage 1 troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| `Error: Bundler "MSI Installer" (msi) failed to produce a bundle.` | WiX 3.x not on `PATH`. Add `C:\Program Files (x86)\WiX Toolset v3.11\bin` and reopen the shell. |
| `Error: Invalid Option: [--win-upgrade-uuid]` | Using WiX 4.x instead of 3.x. Downgrade WiX. |
| `Error: Version [17-0.32.1] contains invalid component` | Forgot to replace `-` with `.` in `--app-version`. Use `17.0.32.1`. |
| App launches then closes silently | Run from `cmd.exe` to see the exit code; usually a missing native library (Stage 0.3 did not pick up the Windows-classifier jars — make sure `installDistLinux` was *not* run). |
| YoGraphic dropdown is empty | `org.reflections` cannot see the application jars. Ensure all jars from `lib/` were copied into `--input` (the default behaviour) and no security sandboxing is blocking `getResources()`. |
| `JavaFX runtime components are missing` error | A non-modular invocation problem: confirm `--main-class` points at a class that does **not** extend `javafx.application.Application` directly (this codebase's `SessionVisualizer` is a regular class — correct). |
| Console window flashes on GUI launcher | Don't pass `--win-console` to the primary launcher; it is unset by default. |
| No console on MCAP launcher | The `MCAPRepackApplication.properties` file is missing `win-console=true`, or the file path passed to `--add-launcher` is wrong. |

---

## Stage 2 — `jlink`-trimmed runtime + `jpackage`

Goal: replace the full JRE bundled by Stage 1 (≈170 MB) with a minimal
runtime image containing only the JDK and JavaFX modules actually used
by the application (≈60–90 MB). Total package size drops from ~300–400
MB to ~150–220 MB.

This stage **does not** require source changes. All third-party
dependencies stay on the classpath (none of them are JPMS-named modules
and adding `module-info.java` to the SCS2 modules is out of scope —
see the Follow-ups section).

### 2.1 Strategy

`jlink` only links explicitly named modules; everything else stays on
classpath. Therefore:

1. Use `jdeps` against the built jars to discover which JDK + JavaFX
   modules the code references.
2. Build a runtime image containing only those modules.
3. Pass that image to `jpackage` via `--runtime-image` instead of
   relying on the default (the running JDK).

### 2.2 JavaFX `jmods`

The JavaFX modules required by the build are pinned at **17.0.8** (see
`scs2-session-visualizer-jfx/build.gradle.kts`). The Maven
`org.openjfx:javafx-*` artifacts shipped to the classpath are **jars**,
not jmods — `jlink` cannot consume them. Download the matching
`openjfx-17.0.8_windows-x64_bin-jmods.zip` from Gluon (see Stage 0.1)
and set:

```bat
set JAVAFX_JMODS_DIR=C:\javafx-jmods-17.0.8
```

### 2.3 Discover required modules with `jdeps`

From the repository root, after running `installDist`:

```bat
jdeps ^
  --module-path "%JAVA_HOME%\jmods;%JAVAFX_JMODS_DIR%" ^
  --multi-release 17 ^
  --ignore-missing-deps ^
  --print-module-deps ^
  --class-path "scs2-session-visualizer-jfx\build\install\scs2-session-visualizer-jfx\lib\*" ^
  scs2-session-visualizer-jfx\build\install\scs2-session-visualizer-jfx\lib\scs2-session-visualizer-jfx-17-0.32.1.jar
```

Record the output. For this codebase it will be a subset of (verify by
running the command — do not hand-edit the list):

```
java.base, java.compiler, java.datatransfer, java.desktop, java.logging,
java.management, java.naming, java.net.http, java.prefs, java.rmi,
java.scripting, java.security.jgss, java.sql, java.xml, jdk.unsupported,
javafx.base, javafx.controls, javafx.fxml, javafx.graphics, javafx.swing
```

`--ignore-missing-deps` is necessary because some third-party jars
(`reflections`, `okhttp`, `gson`) reference optional types that are not
on the classpath; without the flag `jdeps` fails outright.

### 2.4 Build the runtime image

Use the module list discovered in 2.3. The set below is the
conservative baseline known to cover this codebase's runtime needs and
includes a few modules that `jdeps --print-module-deps` may not
surface but the app touches indirectly (HTTPS, secure random, common
charsets):

```bat
jlink ^
  --module-path "%JAVA_HOME%\jmods;%JAVAFX_JMODS_DIR%" ^
  --add-modules java.base,java.compiler,java.datatransfer,java.desktop,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.sql,java.xml,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.localedata,jdk.unsupported,jdk.unsupported.desktop,javafx.base,javafx.controls,javafx.fxml,javafx.graphics,javafx.swing ^
  --strip-debug ^
  --no-man-pages ^
  --no-header-files ^
  --compress=2 ^
  --include-locales=en ^
  --output scs2-session-visualizer-jfx\build\jlink-runtime
```

Notes on module choices specific to this codebase:
- `jdk.crypto.ec` and `jdk.crypto.cryptoki` are required for HTTPS used
  by the version-check feature (`okhttp` calling out to GitHub). Without
  them the version check fails silently on the first run.
- `jdk.localedata` keeps non-en-US locale data available; if you set
  `--include-locales=en` you can omit this to save ~10 MB.
- `jdk.unsupported` is required because some IHMC native-buffer code
  uses `sun.misc.Unsafe`.
- `jdk.unsupported.desktop` is required by JavaFX Swing interop
  (`SwingNode`).
- `java.scripting` is included defensively — some `gson` reflection
  paths probe for `javax.script` types.

After running, verify the runtime works as a standalone JDK image:

```bat
scs2-session-visualizer-jfx\build\jlink-runtime\bin\java.exe -version
```

### 2.5 `jpackage` invocations using the trimmed runtime

The Stage 1 invocations are reused **verbatim** with one added argument:

```
--runtime-image scs2-session-visualizer-jfx\build\jlink-runtime
```

inserted before `--add-launcher`. Direct the output to a different
folder so Stage 1 and Stage 2 builds can coexist for comparison:

- `--dest scs2-session-visualizer-jfx\deployment\windows\app-image-jlink`
- `--dest scs2-session-visualizer-jfx\deployment\windows\msi-jlink`

### 2.6 Stage 2 validation checklist

Repeat every check from Stage 1.5. In addition:

1. **Compare size**:
   ```bat
   dir /s /-c scs2-session-visualizer-jfx\deployment\windows\app-image
   dir /s /-c scs2-session-visualizer-jfx\deployment\windows\app-image-jlink
   ```
   The `-jlink` variant should be at least 30% smaller.
2. **HTTPS feature**: trigger the version-check (Help → Check for
   updates, or whatever menu item invokes
   `okhttp` against GitHub). It must complete without an SSL handshake
   error. If it fails, `jdk.crypto.ec` was omitted from `--add-modules`.
3. **Swing interop**: any dialog that hosts AWT/Swing content (e.g. JFR
   thumbnail rendering paths via `SwingNode`) must render correctly.
   Failures indicate `jdk.unsupported.desktop` is missing.
4. **3D scene**: load a session with meshes. The Prism pipeline must
   pick the D3D backend — failure here usually indicates a missing
   JavaFX native DLL, not a `jlink` problem (i.e. the classifier jars
   were stripped).

### 2.7 Stage 2 troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| `Module javafx.controls not found` during `jlink` | `JAVAFX_JMODS_DIR` not set or wrong path. Confirm it contains `javafx.base.jmod` etc. |
| `Module java.base not found` | `%JAVA_HOME%\jmods` missing. JRE-only installations don't ship jmods — install the JDK. |
| SSL handshake failures after install | Missing `jdk.crypto.ec` (or `jdk.crypto.cryptoki` for some endpoints) — add and rebuild. |
| `NoClassDefFoundError: sun.misc.Unsafe` | Missing `jdk.unsupported`. |
| `IllegalAccessError` from JavaFX Swing interop | Missing `jdk.unsupported.desktop`. |
| Time-zone or formatting errors for non-en-US locales | Drop `--include-locales=en` (or expand it to the needed locales) and add `jdk.localedata`. |
| App still huge after jlink | Most of the bulk is in third-party jars (~80 MB) and native DLLs (`ihmc-video-codecs`, JavaFX Prism), not the runtime. Stage 2 reduces only the JRE portion. |

---

## Stage 3 — Gradle integration

Goal: encapsulate the manual `jpackage`/`jlink` invocations of Stages 1
and 2 into Gradle tasks in
`scs2-session-visualizer-jfx/build.gradle.kts`, mirroring the existing
`installDistLinux` and `buildDebianPackage` task style. The result is
reproducible from a single Gradle invocation and survives version
bumps without manual edits.

### 3.1 Task design

Add the following tasks (specification, not source — actual source
goes in the build script):

| Task name | Depends on | Purpose |
|---|---|---|
| `installDistWindows` | `installDist` | Copy `installDist` output to a clean staging dir, dropping non-Windows native classifier jars (parallels `installDistLinux`). |
| `buildJlinkRuntime` | `installDistWindows` | Run `jlink` to produce `build/jlink-runtime`. Inputs: classpath jars + `JAVAFX_JMODS_DIR` system property. |
| `packageWindowsAppImage` | `installDistWindows` | Run `jpackage --type app-image`. Stage 1 deliverable. |
| `packageWindowsAppImageJlink` | `installDistWindows`, `buildJlinkRuntime` | Run `jpackage --type app-image --runtime-image …`. Stage 2 deliverable. |
| `packageWindowsMsi` | `installDistWindows` | Run `jpackage --type msi`. Stage 1 deliverable. |
| `packageWindowsMsiJlink` | `installDistWindows`, `buildJlinkRuntime` | Run `jpackage --type msi --runtime-image …`. Stage 2 deliverable. |
| `buildWindowsPackages` | `packageWindowsAppImage`, `packageWindowsMsi` | Convenience aggregator. |
| `buildWindowsPackagesJlink` | `packageWindowsAppImageJlink`, `packageWindowsMsiJlink` | Convenience aggregator. |

All tasks should be **OS-guarded**: skip with an explanatory message if
`!Os.isFamily(Os.FAMILY_WINDOWS)`, the same way `buildDebianPackage`
checks `Os.FAMILY_UNIX`.

### 3.2 Build-script additions (specification)

The implementation follows the patterns already used for the Linux
flow. Specification details:

1. **Top-level constants** (next to the existing
   `sessionVisualizerExecutableName`):
   ```kotlin
   val windowsUpgradeUuid = "f74c546b-1d1d-4114-9812-809b8eb1412c"
   val windowsInstallerVersion = ihmc.version.replace("-", ".")
   val windowsDeploymentRoot = "${project.projectDir}/deployment/windows"
   val jlinkRuntimeDir = "${project.buildDir}/jlink-runtime"
   ```
   The `windowsUpgradeUuid` value above is the product's canonical
   stable UUID. Do not regenerate or change it between releases — doing
   so breaks in-place upgrades for users who installed previous
   versions.

2. **`installDistWindows`**: copies `build/install/scs2-session-visualizer-jfx/`
   into `$windowsDeploymentRoot/staging/`, then deletes Linux-only
   classifier jars from `lib/`:
   ```
   include("*-linux-*")
   include("*-android-*")
   include("*-ios-*")
   include("*-macosx-*")
   include("*-osx-*")
   ```
   This mirrors `installDistLinux` but with the opposite exclusion set.

3. **`buildJlinkRuntime`**: reads `JAVAFX_JMODS_DIR` from the project
   property of the same name (set via `-PJAVAFX_JMODS_DIR=…` or
   `~/.gradle/gradle.properties`). Fails fast if unset. Executes the
   exact `jlink` invocation from Stage 2.4 via
   `ihmc.exec(ProcessBuilder(…))`.

4. **`packageWindowsAppImage` / `packageWindowsMsi`**: build the
   `jpackage` argument list dynamically. The two tasks share 90% of
   their arguments; factor into a Kotlin helper function
   `fun jpackageArgsCommon(type: String, dest: String): List<String>`.
   The MSI task appends `--win-dir-chooser --win-menu …`. The MCAP
   launcher property file is materialised into
   `$windowsDeploymentRoot/launchers/MCAPRepackApplication.properties`
   at task-execution time with the correct main-jar name substituted
   from `ihmc.version`, the same way `buildDebianPackage` writes
   `control` and `*.desktop` files.

5. **`packageWindowsAppImageJlink` / `packageWindowsMsiJlink`**: same
   as above but with `--runtime-image $jlinkRuntimeDir` appended and
   `--dest` pointing at `app-image-jlink` / `msi-jlink`.

6. **JDK toolchain assertion**: at task-configuration time, fail if
   `JavaVersion.current() < JavaVersion.VERSION_17` to give a clear
   message instead of an obscure `jpackage` error.

7. **Caching**: declare task inputs and outputs explicitly so Gradle
   skips rebuilds when nothing changed:
   - Inputs: the `lib/` directory contents, `scs-icon.ico`, the
     `MCAPRepackApplication.properties` template, `ihmc.version`.
   - Outputs: the per-task `--dest` directory.

8. **OS guard**: every task's `doFirst { … }` checks
   `Os.isFamily(Os.FAMILY_WINDOWS)` and throws
   `GradleException("Windows packaging tasks only run on Windows.")`
   otherwise.

### 3.3 Invocation examples

After the build script is updated:

```bat
gradlew.bat :scs2-session-visualizer-jfx:buildWindowsPackages
```

Builds both Stage 1 deliverables (`app-image` folder + `.msi`).

```bat
gradlew.bat :scs2-session-visualizer-jfx:buildWindowsPackagesJlink ^
            -PJAVAFX_JMODS_DIR=C:\javafx-jmods-17.0.8
```

Builds both Stage 2 deliverables.

### 3.4 Update `docs/Making-a-release.md`

Once Stage 3 is in place, add a step to the release checklist between
the existing step 7 (Debian) and step 8 (GitHub release):

> 7b. **Windows**: on a Windows machine, run
> `gradle :scs2-session-visualizer-jfx:buildWindowsPackages`. Upload the
> `.msi` from
> `scs2-session-visualizer-jfx/deployment/windows/msi/` and zip the
> `app-image` folder for the GitHub release.

This is a documentation-only change and should be done in the same
commit that introduces the Gradle tasks.

---

## End-to-end validation matrix

Run after every release-candidate build, on a clean Windows 10 or 11
machine that does **not** have a JDK installed (to verify the bundled
runtime is sufficient):

| Check | Stage 1 | Stage 2 |
|---|---|---|
| `SCS2SessionVisualizer.exe` launches without console | ✓ | ✓ |
| Main window shows SCS icon, "No Active Session" title | ✓ | ✓ |
| Open MCAP log session | ✓ | ✓ |
| Open YoVariable log session | ✓ | ✓ |
| 3D scene renders (D3D pipeline) | ✓ | ✓ |
| YoGraphic editor dropdown is populated | ✓ | ✓ |
| Load URDF robot model | ✓ | ✓ |
| Load SDF robot model | ✓ | ✓ |
| `MCAPRepackApplication.exe --help` prints help to console | ✓ | ✓ |
| `MCAPRepackApplication.exe` with no args opens file chooser | ✓ | ✓ |
| Remote session connect (data server discovery) | ✓ | ✓ |
| Version check against GitHub (HTTPS) | ✓ | ✓ (verifies `jdk.crypto.ec`) |
| Start Menu + desktop shortcut work | MSI only | MSI only |
| Upgrade install replaces previous version | MSI only | MSI only |
| Uninstall removes program files | MSI only | MSI only |
| Bundle size meets target | ~300–400 MB | ~150–220 MB |

---

## Follow-ups (out of scope for this plan)

These items are noted explicitly so they are not forgotten, but are
**not** part of the initial Windows-executable work:

1. **Code signing.** Sign `SCS2SessionVisualizer.exe`,
   `MCAPRepackApplication.exe`, and the `.msi` with an Authenticode
   certificate using `signtool.exe`. This eliminates the Windows
   SmartScreen warning. Requires:
   - An EV or OV code-signing certificate.
   - `signtool.exe` from the Windows SDK on `PATH`.
   - A new Gradle task `signWindowsArtifacts` that runs after the
     packaging tasks and before any upload step.
   - A secure mechanism for the certificate (HSM / smart card /
     Azure Key Vault).

2. **CI automation.** Extend
   `.github/workflows/main-gradleCI-build.yml` (or add a new release
   workflow) with a `windows-latest` job that:
   - Checks out the tag.
   - Sets up Temurin 17 via `actions/setup-java`.
   - Downloads JavaFX 17.0.8 jmods from Gluon.
   - Installs WiX 3.x.
   - Runs `gradlew.bat :scs2-session-visualizer-jfx:buildWindowsPackagesJlink`.
   - Uploads the `.msi` as a release asset using
     `softprops/action-gh-release`.
   Note that the existing CI workflow only runs tests on Ubuntu;
   release packaging is currently manual on all platforms.

3. **ARM64 (Windows on ARM).** Currently out of scope because:
   - JavaFX 17.0.8 does not ship Windows ARM64 native libraries.
   - The IHMC native dependencies (`ihmc-video-codecs`) likely lack
     ARM64 builds.
   Revisit when JavaFX 21+ adoption and IHMC native rebuilds catch up.

4. **Full JPMS modularisation.** Adding `module-info.java` to
   `scs2-definition`, `scs2-session`, `scs2-session-visualizer`, and
   `scs2-session-visualizer-jfx` would allow `jlink` to bundle the
   application itself into the runtime image, dropping size by another
   30–50 MB. Substantial effort because every transitive automatic
   module would need either real `module-info.java` files upstream or
   `--add-reads ALL-UNNAMED` shims.

5. **Auto-update.** None of `jpackage` outputs include an update
   mechanism. If autoupdate becomes a requirement, options are:
   - Bake the existing version-check feature into a self-updating
     wrapper.
   - Re-evaluate alternative installer frameworks (Squirrel.Windows,
     install4j) that provide update infrastructure.

6. **Per-launcher icons.** `MCAPRepackApplication.exe` currently reuses
   the main SCS icon. Add a dedicated MCAP `.ico` and reference it via
   the launcher properties file if differentiation is desired.

7. **`.exe` installer via Inno Setup.** WiX 3.x is sufficient for the
   stated needs. If a non-elevated per-user installer is later
   required, add Inno Setup 6 as an alternative `--type exe` path.
