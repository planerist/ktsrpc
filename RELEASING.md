# Releasing a New Version

1. Bump `version` in the root `build.gradle.kts`:
   ```kotlin
   allprojects {
       version = "0.1.4" // ← increment here
   }
   ```
2. Update the dependency version in `README.md` (Quick Start → Add Dependencies):
   ```kotlin
   implementation("com.github.planerist.ktsrpc:rpc-protocol:v0.1.4")
   implementation("com.github.planerist.ktsrpc:rpc-gen:v0.1.4")
   ```
3. Commit and push:
   ```bash
   git add build.gradle.kts README.md
   git commit -m "bump version to 0.1.4"
   git push
   ```
4. Create and push a matching tag:
   ```bash
   git tag v0.1.4
   git push --tags
   ```
5. JitPack will automatically build the new version. Check status at:
   https://jitpack.io/#planerist/ktsrpc
