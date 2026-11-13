The wrapper scripts and JAR are the unmodified Gradle v8.11.1 upstream files:
https://github.com/gradle/gradle/tree/v8.11.1/gradle/wrapper

gradle-wrapper.jar SHA-256:
`2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`

Verified against the official checksum:
https://services.gradle.org/distributions/gradle-8.11.1-wrapper.jar.sha256

The binary distribution's SHA-256 is also enforced by
`distributionSha256Sum` in gradle-wrapper.properties. Both checksum sources were
retrieved over HTTPS from Gradle on 2026-11-13. No Gradle/Android build was run to
generate these wrapper files.
