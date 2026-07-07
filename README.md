# ApiThrottle

A tiny Kotlin Multiplatform rate-limiter and priority dispatcher for API calls.
Zero dependencies beyond `kotlinx-coroutines`. Targets JVM, Android, and iOS.

- **Token bucket** — caps how many requests *start* per second (starts full, so the first burst is immediate).
- **Semaphore** — caps how many requests are *in flight* at once.
- **Priority queue** — `HIGH` calls (e.g. a screen the user is looking at) jump ahead of `LOW` background batches.

## Install (JitPack)

Add JitPack to your repositories:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Add the dependency:

```kotlin
// build.gradle.kts
commonMain.dependencies {
    implementation("com.github.GoldenGentleman:ApiThrottle:0.1.0")
}
```

## Usage

```kotlin
val throttle = ApiThrottle(
    name = "IGDB",
    requestsPerSecond = 4,
    maxConcurrent = 8,
    scope = applicationScope,
)

// Foreground — jumps ahead of background work
val game = throttle.execute(ApiThrottle.Priority.HIGH) { igdb.fetchGame(id) }

// Background batch — LOW is the default
val cover = throttle.execute { igdb.fetchCover(id) }
```

Create one instance per rate-limited API. Cancelling the calling coroutine removes the
request from the queue if it hasn't started yet.

## License

MIT
