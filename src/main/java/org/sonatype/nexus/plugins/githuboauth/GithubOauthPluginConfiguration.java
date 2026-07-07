package org.sonatype.nexus.plugins.githuboauth;

import org.apache.shiro.mgt.RealmSecurityManager;
import org.apache.shiro.realm.Realm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

/**
 * Registers the GitHub OAuth realm into Shiro at application startup.
 *
 * <p>Nexus's {@code SpringComponentScan} and custom bean factory do not
 * reliably process {@code @Autowired} constructors for classes in nested jars
 * or extracted {@code BOOT-INF/classes}. Instead of fighting the scanner, we
 * manually wire the plugin beans and register the realm directly with Shiro's
 * {@link RealmSecurityManager} after the Spring context is refreshed.</p>
 */
@Component
public class GithubOauthPluginConfiguration implements ApplicationListener<ContextRefreshedEvent> {

    private static final Logger log = LoggerFactory.getLogger(GithubOauthPluginConfiguration.class);

    private volatile boolean registered = false;

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (registered) {
            return;
        }
        try {
            log.info("Registering GitHub OAuth realm...");

            // Manually wire the plugin beans (bypass Spring DI)
            GithubOauthConfiguration config = new GithubOauthConfiguration();
            GithubApiClient client = new GithubApiClient(config);
            GithubOauthAuthenticatingRealm realm = new GithubOauthAuthenticatingRealm(client);

            // Register with Shiro
            RealmSecurityManager securityManager = event.getApplicationContext()
                    .getBean(RealmSecurityManager.class);
            for (Realm existing : securityManager.getRealms()) {
                if (existing.getName().equals(realm.getName())) {
                    log.info("GitHub OAuth realm already registered, skipping");
                    registered = true;
                    return;
                }
            }
            securityManager.getRealms().add(realm);
            log.info("GitHub OAuth realm registered successfully: {}", realm.getName());
            registered = true;
        } catch (Exception e) {
            log.error("Failed to register GitHub OAuth realm", e);
        }
    }
}
