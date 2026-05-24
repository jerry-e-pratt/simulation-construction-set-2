# Making a Release of Simulation Construction Set 2
1. Bump the version in `group.gradle.properties`
2. Commit only `group.gradle.properties` with a message in the format: "`:bookmark: <version>`"
3. Create a tag with the version name
4. Push the release commit and tag
5. Ensure publishing credentials for IHMC robotlabfiles are set in your `~/.gradle/gradle.properties` file.
6. Publish using `gradle compositePublish -PpublishUrl=robotlabfiles`
7. Build a Debian .deb installer using `cd docker/debian; ./buildDebianInstaller.sh`
8. Build the Windows .msi installer on a Windows machine that has a JDK 17+ providing `jpackage` and WiX Toolset 3.x installed: `gradlew.bat :scs2-session-visualizer-jfx:buildWindowsPackages`. See `docs/executable-plan.md` for the one-time WiX/JDK setup. The MSI is written to `scs2-session-visualizer-jfx/deployment/windows/msi/SCS2SessionVisualizer-<version>.msi`.
9. Create a release on GitHub documenting the changes (following the format of existing releases)
10. Upload the .deb (located in `scs2-session-visualizer-jfx/deployment/debian`) and the .msi (located in `scs2-session-visualizer-jfx/deployment/windows/msi`) created previously to the new GitHub release
11. Announce the release to whoever may be interested
