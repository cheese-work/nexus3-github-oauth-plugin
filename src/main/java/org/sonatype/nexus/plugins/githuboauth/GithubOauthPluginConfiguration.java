package org.sonatype.nexus.plugins.githuboauth;

import org.springframework.stereotype.Component;

/**
 * Marker component that triggers Nexus's {@code SpringComponentScan} to
 * discover the plugin classes under the scanned {@code org.sonatype.nexus.*}
 * namespace.
 *
 * <p>All plugin classes now live directly under this package (or its
 * sub-packages) so they are discovered by Nexus's standard component scanner
 * without needing {@code @ComponentScan} or
 * {@code BeanDefinitionRegistryPostProcessor} workarounds.</p>
 */
@Component
public class GithubOauthPluginConfiguration {
}
