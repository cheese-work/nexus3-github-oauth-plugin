# Port to Nexus Repository 3.93.2+ (Spring runtime)

This branch ports the plugin to **Sonatype Nexus Repository 3.93.2** (and the
3.71+ line in general).

## Why a port was needed

Nexus 3.71 replaced the **Karaf/OSGi** runtime with a **Spring Boot** runtime.
The original plugin was built as an OSGi bundle (`.kar`) with `javax.inject` /
Sisu `@Named` DI and the Karaf `nexus-plugins` 3.43 parent. None of that works
on 3.93.2:

- The old `org.sonatype.nexus:nexus-plugin-api` / `nexus-security` /
  `nexus-capability` artifacts are no longer published to Maven Central at
  3.93.2-01 (they moved to the new monorepo group
  `org.sonatype.nexus.common.components`).
- The `/deploy/` directory is **still** where drop-in plugins go, but the
  runtime now loads them via Spring component scanning, not Karaf features.

## What changed

| Area | Before (3.43 / Karaf) | After (3.93.2 / Spring) |
| --- | --- | --- |
| Packaging | `.kar` (Karaf archive) | plain shaded `jar` |
| Parent POM | `org.sonatype.nexus.plugins:nexus-plugins:3.43.0-01` | `org.sonatype.nexus.common.components:nexus-common-components-parent:3.93.2-01` |
| DI annotations | `javax.inject.Named`, `com.google.inject.Singleton` | `@Component`, `@Qualifier` (Spring) |
| `@Description` | `org.eclipse.sisu.Description` | `org.sonatype.nexus.common.Description` |
| Realm registration | Karaf feature XML + OSGi bundle | auto-discovered: `RealmManagerImpl` collects all Spring `Realm` beans via `@Qualifier` |

The GitHub authz logic (`GithubApiClient`, org/team/role mapping, principal
caching) is **unchanged** — only the DI wiring and build packaging changed.

## How realms are discovered

`org.sonatype.nexus.security.internal.RealmManagerImpl` is injected with
`List<Realm> availableRealmsList` and builds a qualifier map via
`QualifierUtil.buildQualifierBeanMap(...)`. Any Spring `@Component` that
extends Shiro `Realm` and carries a `@Qualifier` is picked up automatically and
appears on **Administration → Security → Realms** — no extra wiring needed.

## Build

Requires JDK 21+ and Maven 3.9+. (The full Nexus reactor needs JDK 25; this
plugin only targets `--release 21`, which is the runtime JVM for 3.93.2.)

```bash
mvn -Denforcer.skip=true clean package
# artifact: target/nexus3-github-oauth-plugin-3.93.2-01.jar  (shaded, self-contained)
```

`-Denforcer.skip=true` bypasses the Nexus parent's reactor-wide `RequireJavaVersion [25,)`
rule, which doesn't apply to a standalone plugin module.

## Deploy

1. Stop Nexus (brief restart window).
2. **Remove the old `.kar`** from `$NEXUS_HOME/deploy/` if present (it is
   ignored by the new runtime but should be cleaned up):
   ```bash
   rm -f /opt/nexus/deploy/nexus3-github-oauth-plugin-*.kar
   ```
3. Drop in the new jar:
   ```bash
   cp target/nexus3-github-oauth-plugin-3.93.2-01.jar /opt/nexus/deploy/
   chown nexus:nexus /opt/nexus/deploy/nexus3-github-oauth-plugin-3.93.2-01.jar
   ```
4. Start Nexus.
5. Verify: **Administration → Security → Realms** — `Github Enterprise
   Authentication Realm` should be listed in "Available" and moved to
   "Active".

## Configure

The plugin still reads `$NEXUS_HOME/etc/githuboauth.properties` (or
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
(PLT) as the password. Roles are mapped as `<org>/<team>`.
