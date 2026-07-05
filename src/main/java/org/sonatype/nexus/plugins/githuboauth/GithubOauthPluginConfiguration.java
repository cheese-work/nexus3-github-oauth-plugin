package org.sonatype.nexus.plugins.githuboauth;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Bridge that lets Nexus 3.71+ (Spring runtime) discover the GitHub OAuth realm.
 *
 * <p>Nexus's {@code SpringComponentScan} only scans the {@code org.sonatype.nexus}
 * and {@code com.sonatype.nexus} packages (see
 * {@code JAVA_PACKAGES_FOR_NEXUS_SCANNING} in
 * {@code nexus-bootstrap-spring}). The realm implementation lives under
 * {@code com.larscheidschmitzhermes.nexus3.github.oauth.plugin}, so it would never
 * be component-scanned on its own.</p>
 *
 * <p>This class deliberately lives in the scanned {@code org.sonatype.nexus.*}
 * namespace and re-exports the plugin's package via {@link ComponentScan}, mirroring
 * the pattern the Nexus javadoc describes: "Each of the modules may potentially do
 * {@code @ComponentScan} of their own module for injection if custom scanning is
 * required."</p>
 */
@Configuration
@ComponentScan(basePackages = "com.larscheidschmitzhermes.nexus3.github.oauth.plugin")
public class GithubOauthPluginConfiguration {
}
