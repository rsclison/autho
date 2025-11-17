---
description: Initialize Leiningen environment and download dependencies for testing
---

# Session Start Hook - Leiningen Environment Setup

This hook ensures that the development environment is ready for running tests.

## Tasks

1. **Check Leiningen Installation**
   - Verify that `./lein` script exists and is executable
   - If Leiningen standalone JAR is missing, inform the user

2. **Download Project Dependencies**
   - Run `./lein deps` to download all project dependencies including RocksDB
   - This ensures that native libraries are available for tests
   - Skip if dependencies were recently downloaded (check target/ directory age)

3. **Verify RocksDB Dependency**
   - Confirm that RocksDB JNI library is in the local Maven repository
   - Check for `~/.m2/repository/org/rocksdb/rocksdbjni/8.11.4/`

4. **Report Status**
   - Summarize what was installed/verified
   - If there are issues, provide clear next steps for the user

## Environment Information

- **Project**: autho (XACML Authorization Server)
- **Build Tool**: Leiningen 2.11.2
- **Key Dependency**: RocksDB 8.11.4 (requires native library)
- **Test Command**: `./lein test`

## Notes

- The `./lein` wrapper script is checked into the repository
- Leiningen will auto-download its standalone JAR on first use
- RocksDB native libraries are platform-specific (linux64 in this environment)
- Dependencies are cached in `~/.m2/repository/`
