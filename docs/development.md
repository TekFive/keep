# Development and releases

[Usage guide index](README.md) · [KEEP README](../README.md)

[![Gradle logo](https://github.com/gradle.png?size=64)](https://gradle.org/)

[![Docker logo](https://github.com/docker.png?size=64)](https://www.docker.com/)

[![GitHub logo](https://github.githubassets.com/images/modules/logos_page/GitHub-Mark.png)](https://github.com/)

[![JitPack logo](https://github.com/jitpack.png?size=64)](https://jitpack.io/)

Run the test suite:

```bash
./gradlew test
```

The audit persistence tests can use an existing PostgreSQL instance without starting
containers. Set `KEEP_AUDIT_TEST_JDBC_URL`, `KEEP_AUDIT_TEST_JDBC_USER`, and
`KEEP_AUDIT_TEST_JDBC_PASSWORD`, then run:

```bash
./gradlew test --tests 'org.tekfive.keep.audit.*'
```

The persistence tests create a temporary schema and remove it afterward. The supplied
database role needs permission to create schemas. Without the URL, those tests are
skipped and the audit declaration tests still run.

To develop against sibling source checkouts instead of JitPack artifacts, place ACK, JFK, and KViash beside this repository and enable local-project substitution:

```bash
./gradlew -Pkeep.useLocalProjects=true test
```

`KEEP_USE_LOCAL_PROJECTS=true` provides the same switch for environments where an environment variable is more convenient.

Publish to the local Maven repository:

```bash
./gradlew publishToMavenLocal
```

Generate a jar:

```bash
./gradlew jar
```

To release and publish, open **Actions → release-and-publish → Run workflow** in
GitHub. The workflow uses the version in `build.gradle.kts`, runs the tests,
creates the GitHub release and version tag, verifies publication on JitPack,
then publishes to GitHub Packages. Publishing to GitHub Packages runs only if
the release workflow succeeds. The separate `release` and `publish` workflows
can also be run manually.
