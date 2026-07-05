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
| Discovery | Karaf feature XML + OSGi bundle | bridge `@Configuration` under `org.sonatype.nexus.*` + `loader.path` (see below) |

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

The launch main class, however, **is**
`org.springframework.boot.loader.launch.PropertiesLauncher` (Spring Boot 3.5),
which supports `loader.path` to add external jars/directories to the boot
classloader. Combined with a small bridge `@Configuration` placed under the
scanned `org.sonatype.nexus.*` namespace, this is the working deploy path.

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

Because the classpath is built from `${KARAF_HOME}/bin/*.jar` only, the plugin
jar must be added via Spring Boot `loader.path`. Pick **one** of the options
below.

### Option A — `loader.path` via `bin/setenv` (recommended, no jar editing)

1. Copy the plugin jar into the Nexus install:
   ```bash
   sudo mkdir -p /opt/nexus/lib/ext
   sudo cp target/nexus3-github-oauth-plugin-3.93.2-01.jar /opt/nexus/lib/ext/
   sudo chown -R nexus:nexus /opt/nexus/lib/ext
   ```
2. Tell `PropertiesLauncher` to load it. Edit `/opt/nexus/bin/setenv`
   (sourced by `bin/nexus`):
   ```bash
   export JAVA_OPTS="${JAVA_OPTS} -Dloader.path=lib/ext/nexus3-github-oauth-plugin-3.93.2-01.jar"
   ```
   (`loader.path` accepts comma-separated `jar`/`dir`/`dir/` entries relative
   to the boot jar's working dir; the boot jar is `${KARAF_HOME}/bin/*.jar`,
   so paths resolve under `${KARAF_HOME}`.)
3. Remove any stale Karaf artifact that the new runtime ignores:
   ```bash
   rm -f /opt/nexus/deploy/nexus3-github-oauth-plugin-*.kar
   ```
4. Restart Nexus (brief restart window):
   ```bash
   sudo systemctl restart nexus
   ```

### Option B — drop the jar next to the boot jar

The classpath is `${KARAF_HOME}/bin/*.jar`, so simply placing the plugin jar
in `/opt/nexus/bin/` puts it on the classpath with no `loader.path` change:

```bash
sudo cp target/nexus3-github-oauth-plugin-3.93.2-01.jar /opt/nexus/bin/
sudo chown nexus:nexus /opt/nexus/bin/nexus3-github-oauth-plugin-3.93.2-01.jar
sudo systemctl restart nexus
```

This is simpler but mixes plugin code with Nexus's own boot jars; prefer
Option A for cleanliness.

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
