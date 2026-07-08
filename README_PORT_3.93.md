# Port to Nexus Repository 3.93.2+ (Spring runtime)

This branch ports the plugin to **Sonatype Nexus Repository 3.93.2** (and the
3.71+ line in general).

## Why a port was needed

Nexus 3.71 replaced the **Karaf/OSGi** runtime with a **Spring Boot** runtime.

- The old `org.sonatype.nexus:nexus-plugin-api` / `nexus-security` /
  `nexus-capability` artifacts are no longer published to Maven Central at
  3.93.2-01 (they moved to the new monorepo group
  `org.sonatype.nexus.common.components`).
- The Karaf `.kar` packaging / OSGi bundle loader / `nexus-plugins` 3.43
  parent no longer apply.

## What changed

| Area | Before (3.43 / Karaf) | After (3.93.2 / Spring) |
| --- | --- | --- |
| Packaging | `.kar` (Karaf archive) | plain shaded `jar` |
| Parent POM | `org.sonatype.nexus.plugins:nexus-plugins:3.43.0-01` | `org.sonatype.nexus.common.components:nexus-common-components-parent:3.93.2-01` |
| DI annotations | `javax.inject.Named`, `com.google.inject.Singleton` | `@Component`, `@Qualifier` (Spring) |
| `@Description` | `org.eclipse.sisu.Description` | `org.sonatype.nexus.common.Description` |
| Discovery | Karaf feature XML + OSGi bundle | bridge `@Configuration` under `org.sonatype.nexus.*` + boot-jar embed (see below) |

The GitHub authz logic (`GithubApiClient`, org/team/role mapping, principal
caching) is **unchanged** — only the DI wiring, build packaging, and deploy
mechanism changed.

## How plugin loading actually works in 3.93.2 (verified from source)

This was investigated against the `sonatype/nexus-public` tag
`release-3.93.2-01`. The earlier "drop jar in `/opt/nexus/deploy/` and Spring
will scan it" claim is **incorrect** for 3.93.2. Two facts drive the real
mechanism:

1. **Classpath does not include `/deploy/`.** `bin/nexus`'s `setupClassPath()`
   builds the classpath from `${KARAF_HOME}/bin/*.jar` only — there is no
   Karaf deploy scanner anymore. A jar placed in `/opt/nexus/deploy/` is
   silently ignored.
2. **`SpringComponentScan` only scans two packages.** See
   `nexus-bootstrap-spring` .../`SpringComponentScan.java`:
   ```java
   private static final String[] JAVA_PACKAGES_FOR_NEXUS_SCANNING =
       {"org.sonatype.nexus", "com.sonatype.nexus"};
   ```
   It is a `private static final` array — **not** overridable by property or
   system variable. So even if a plugin jar is on the classpath, classes in
   `com.larscheidschmitzhermes.*` will never be component-scanned.

The live Nexus 3.93.2 launcher, however, is **not** `PropertiesLauncher`.
Its manifest uses `org.springframework.boot.loader.launch.JarLauncher`, and
the generated `/opt/nexus/bin/nexus` script runs it with `java -jar`.
`JarLauncher` ignores `loader.path`, and `java -jar` ignores ordinary external
`-classpath` additions. The reliable deployment path is therefore to embed the
plugin jar into the executable Nexus boot jar as `BOOT-INF/lib/<plugin>.jar`
and append it to `BOOT-INF/classpath.idx`. Combined with a small bridge
`@Configuration` placed under the scanned `org.sonatype.nexus.*` namespace,
this makes the realm visible to Spring component scanning.

### The bridge config

`src/main/java/org/sonatype/nexus/plugins/githuboauth/GithubOauthPluginConfiguration.java`
lives in the scanned namespace and re-exports the plugin package:

```java
@Configuration
@ComponentScan(basePackages = "com.larscheidschmitzhermes.nexus3.github.oauth.plugin")
public class GithubOauthPluginConfiguration { }
```

This mirrors what `SpringComponentScan`'s own javadoc describes: *"Each of the
modules may potentially do `@ComponentScan` of their own module for injection
if custom scanning is required."* Once the bridge bean is registered, the
realm/component beans it imports are picked up by `RealmManagerImpl` (which is
injected with `List<Realm>` and keys them via
`QualifierUtil.buildQualifierBeanMap`), so the realm appears on
**Administration → Security → Realms** with no extra wiring.

## Build

Requires **JDK 21+** (the 3.93.2 runtime JVM) and **Maven 3.9.6+**. The full
Nexus reactor requires JDK 25, but this standalone plugin module only targets
`--release 21`, so the parent's reactor-wide `RequireJavaVersion [25,)` rule
is skipped. (Only JDK 21 is needed at runtime; Nexus 3.93.2 ships Temurin 21.)

```bash
./mvnw -Denforcer.skip=true clean package
# artifact: target/nexus3-github-oauth-plugin-3.93.2-01.jar  (shaded, self-contained)
```

`-Denforcer.skip=true` bypasses the Nexus parent's reactor-wide
`RequireJavaVersion [25,)` and `requireMavenVersion [3.9.6,)` rules, which
do not apply to a standalone plugin module targeting `--release 21`.

> The Maven wrapper is pinned to **3.9.9** (`.mvn/wrapper/maven-wrapper.properties`).
> The upstream `3.3.3` wrapper violated the parent's `[3.9.6,)` Maven rule.

## Deploy

Nexus 3.93.2 is launched by `JarLauncher`:

```text
Main-Class: org.springframework.boot.loader.launch.JarLauncher
java ... -jar /opt/nexus/bin/sonatype-nexus-repository-3.93.2-01.jar
```

That means the old Karaf `/deploy` scanner is gone, `loader.path` is ignored,
and adding an external `-classpath` is ineffective. The plugin must be embedded
into the Nexus executable boot jar under `BOOT-INF/lib/` and listed in
`BOOT-INF/classpath.idx`.

Use the checked-in installer so the boot jar is backed up, patched, and
validated consistently:

```bash
./mvnw -Denforcer.skip=true -DskipTests clean package

# Run on the Nexus host, or copy both the jar and script there first.
sudo ./scripts/install-nexus-3.93-plugin.sh \
  --plugin target/nexus3-github-oauth-plugin-3.93.2-01.jar \
  --nexus-home /opt/nexus

# Restart during an approved maintenance window.
sudo systemctl restart nexus
```

The installer performs these steps:

1. Finds `/opt/nexus/bin/sonatype-nexus-repository-*.jar`.
2. Writes a timestamped backup next to the boot jar.
3. Removes any older embedded `nexus3-github-oauth-plugin-*.jar`.
4. Adds the new plugin jar as `BOOT-INF/lib/nexus3-github-oauth-plugin-3.93.2-01.jar`.
5. Appends that nested jar to `BOOT-INF/classpath.idx`.
6. Validates the patched boot jar contains both the nested jar and classpath
   index entry.

For one-shot installs you can let the script restart Nexus after patching:

```bash
sudo ./scripts/install-nexus-3.93-plugin.sh \
  --plugin target/nexus3-github-oauth-plugin-3.93.2-01.jar \
  --nexus-home /opt/nexus \
  --restart
```

Rollback is just replacing the patched boot jar with the timestamped backup and
restarting Nexus:

```bash
sudo cp /opt/nexus/bin/sonatype-nexus-repository-3.93.2-01.jar.backup.<timestamp> \
  /opt/nexus/bin/sonatype-nexus-repository-3.93.2-01.jar
sudo systemctl restart nexus
```

Remove stale Karaf artifacts if present; Nexus 3.93 ignores them but they are
confusing during troubleshooting:

```bash
sudo rm -f /opt/nexus/deploy/nexus3-github-oauth-plugin*.kar
```

## Verify

After restart, confirm the realm is registered.

**UI:** *Administration → Security → Realms* — `Github Enterprise
Authentication Realm` should be listed under "Available"; move it to "Active".

**REST:**
```bash
curl -s -u admin:<password> http://127.0.0.1:8081/service/rest/v1/security/realms/available | grep -i github
```

Then check `nexus.log` for the realm init line:
```bash
grep -i "Github oAuth Realm initialized" /opt/sonatype-work/nexus3/log/nexus.log
```

If the realm does **not** appear, the two most likely causes (in order):
1. The jar is not actually on the boot classpath — check `ps -ef | grep nexus`
   for the `-Dloader.path=...` flag (Option A) or the jar in the `-classpath`
   value (Option B).
2. The bridge class was not scanned — the boot classloader did not pick up the
   `org/sonatype/nexus/plugins/githuboauth/` entries. Confirm with:
   ```bash
   unzip -l /opt/nexus/lib/ext/nexus3-github-oauth-plugin-3.93.2-01.jar | grep githuboauth/GithubOauthPluginConfiguration
   ```

## Configure

The plugin reads `$NEXUS_HOME/etc/githuboauth.properties` (or
`/opt/sonatype-work/nexus3/etc/githuboauth.properties`). Example:

```properties
github.api.url=https://github.example.com/api/v3
github.org=YOUR-ORG
github.principal.cache.ttl=PT1M
request.timeout.connect=-1
request.timeout.connection-request=-1
request.timeout.socket=-1
```

Users then log in with their **GitHub username** and a **personal access token**
(PAT) as the password. Roles are mapped as `<org>/<team>`.

## Acceptance status

| # | Criterion | Status |
| --- | --- | --- |
| 1 | `mvn clean package` succeeds reproducibly | ✅ (JDK 21 + Maven 3.9.9, `-Denforcer.skip=true`; documented) |
| 2 | Artifact loads without classpath errors | ⏳ mechanism identified (`loader.path` + bridge `@Configuration`); deploy needs SSH to verify |
| 3 | `GithubOauthAuthenticatingRealm` in available realms | ⏳ pending live deploy |
| 4 | GitHub PAT authenticates a user | ⏳ pending live deploy |
| 5 | PR updated with build + deploy docs | ✅ this file |

Items 2–4 require SSH to `vps-langfuse` (Nexus host) to deploy and verify; see
the issue handoff.
