package org.sonatype.nexus.plugins.githuboauth;

import com.larscheidschmitzhermes.nexus3.github.oauth.plugin.GithubOauthAuthenticatingRealm;
import com.larscheidschmitzhermes.nexus3.github.oauth.plugin.api.GithubApiClient;
import com.larscheidschmitzhermes.nexus3.github.oauth.plugin.configuration.GithubOauthConfiguration;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Bridge that registers the GitHub OAuth plugin beans into the Nexus Spring context.
 *
 * <p>Nexus's {@code SpringComponentScan} only scans {@code org.sonatype.nexus}
 * and {@code com.sonatype.nexus}. This class lives in the scanned namespace and
 * uses a {@link BeanDefinitionRegistryPostProcessor} to programmatically register
 * all plugin beans before the application context is fully initialized.</p>
 *
 * <p>The {@code @ComponentScan}/{@code @Bean} approaches don't work reliably with
 * Nexus's custom {@code JavaxProviderDefaultListableBeanFactory}, so we fall back
 * to direct {@code BeanDefinition} registration — the lowest-level Spring
 * extension point.</p>
 */
@Component
public class GithubOauthPluginConfiguration implements BeanDefinitionRegistryPostProcessor {

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        // 1. Register GithubOauthConfiguration (no deps)
        registry.registerBeanDefinition(
                "githubOauthConfiguration",
                BeanDefinitionBuilder.rootBeanDefinition(GithubOauthConfiguration.class)
                        .getBeanDefinition());

        // 2. Register GithubApiClient (depends on GithubOauthConfiguration)
        BeanDefinition apiClientDef = BeanDefinitionBuilder
                .rootBeanDefinition(GithubApiClient.class)
                .addConstructorArgReference("githubOauthConfiguration")
                .getBeanDefinition();
        registry.registerBeanDefinition("githubApiClient", apiClientDef);

        // 3. Register GithubOauthAuthenticatingRealm (depends on GithubApiClient)
        BeanDefinition realmDef = BeanDefinitionBuilder
                .rootBeanDefinition(GithubOauthAuthenticatingRealm.class)
                .addConstructorArgReference("githubApiClient")
                .getBeanDefinition();
        registry.registerBeanDefinition(
                GithubOauthAuthenticatingRealm.NAME, realmDef);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // No-op
    }
}
