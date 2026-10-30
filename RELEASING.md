# Releasing a New Version

1. Bump `version` in the root `build.gradle.kts`:
   ```kotlin
   allprojects {
       version = "0.2.0" // ← increment here
   }
   ```
2. Update the versions in `README.md`: Quick Start → Add Dependencies, and Swift Client → Add
   the Package:
   ```kotlin
   implementation("com.github.planerist.ktsrpc:rpc-protocol:v0.2.0")
   implementation("com.github.planerist.ktsrpc:rpc-gen:v0.2.0")
   ```
   ```swift
   .package(url: "https://github.com/planerist/ktsrpc.git", exact: "0.2.0"),
   ```
3. If the Swift generator changed, regenerate the Swift files the tests compile, and check:
   ```bash
   KTSRPC_UPDATE_SWIFT=1 ./gradlew :rpc-gen:test --rerun   # swift/Tests/KtsRpcTests/Generated/TestRpc.swift
   ./gradlew examples:schema:generateSwiftRpc               # examples/swift/Sources/ExampleRpc/Rpc.swift
   ./gradlew test && swift test
   ```
4. Commit and push:
   ```bash
   git add build.gradle.kts README.md
   git commit -m "bump version to 0.2.0"
   git push
   ```
5. Create and push a matching tag:
   ```bash
   git tag v0.2.0
   git push --tags
   ```
6. The `Release` workflow runs the Swift tests (on macOS, against the example server) and then
   creates the GitHub release with the JARs. SwiftPM resolves the Swift package from the tag
   itself (`v0.2.0` is version `0.2.0`), so a tag whose Swift tests fail should be deleted and
   made again after the fix.
7. JitPack will automatically build the new version. Check status at:
   https://jitpack.io/#planerist/ktsrpc
