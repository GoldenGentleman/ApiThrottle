# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-07-07

### Added
- Initial release of `ApiThrottle`: a rate-limited, priority-aware coroutine
  dispatcher for API calls.
- Token bucket limiting how many requests start per second (starts full for an
  immediate first burst).
- Semaphore capping the number of in-flight requests.
- `HIGH`/`LOW` priority queue so foreground calls jump ahead of background batches.
- Kotlin Multiplatform targets: JVM, Android, and iOS.

[Unreleased]: https://github.com/GoldenGentleman/ApiThrottle/compare/0.1.0...HEAD
[0.1.0]: https://github.com/GoldenGentleman/ApiThrottle/releases/tag/0.1.0
