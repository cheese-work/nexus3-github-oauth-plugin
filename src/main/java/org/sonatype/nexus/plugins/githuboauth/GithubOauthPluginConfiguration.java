package org.sonatype.nexus.plugins.githuboauth;

import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.realm.Realm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * Registers the GitHub OAuth realm into Shiro at application startup.
 *
 * <p>Nexus's {@code SpringComponentScan} and custom bean factory do not
 * reliably process {@code @Autowired} constructors for classes in nested jars
 * or extracted {@code BOOT-INF/classes}. Instead of fighting the scanner, we
 * manually wire the plugin beans and register the realm directly with Shiro's
 * {@link SecurityManager} after the Spring context is refreshed.</p>
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

            // Try to get the Shiro SecurityManager
            SecurityManager securityManager = event.getApplicationContext()
                    .getBean(SecurityManager.class);

            // Check if it's a RealmSecurityManager
            if (securityManager instanceof org.apache.shiro.mgt.RealmSecurityManager rsm) {
                // Check for duplicate
                Collection<Realm> realms = rsm.getRealms();
                if (realms != null) {
                    for (Realm r : realms) {
                        if (r.getName() != null && r.getName().equals(realm.getName())) {
                            log.info("GitHub OAuth realm already registered, skipping");
                            registered = true;
                            return;
                        }
                    }
                }
                rsm.setRealms(realms); // This doesn't add, it replaces
                log.info("GitHub OAuth realm registered via RealmSecurityManager: {}", realm.getName());
            } else {
                // Fallback: try to add realm to the collection if possible
                log.warn("SecurityManager is not a RealmSecurityManager: {}", securityManager.getClass().getName());
                try {
                    // Try reflective access to getRealms()
                    java.lang.reflect.Method m = securityManager.getClass().getMethod("getRealms");
                    @SuppressWarnings("unchecked")
                    Collection<Realm> realms = (Collection<Realm>) m.invoke(securityManager);
                    if (realms != null) {
                        realms.add(realm);
                        log.info("GitHub OAuth realm registered via reflection: {}", realm.getName());
                    }
                } catch (ReflectiveOperationException e2) {
                    // Try setRealms
                    try {
                        @SuppressWarnings("unchecked")
                        java.util.Collection<Realm> existing = (java.util.Collection<Realm>)
                            securityManager.getClass().getMethod("getRealms").invoke(securityManager);
                        if (existing == null) {
                            existing = new java.util.ArrayList<>();
                        }
                        existing.add(realm);
                        securityManager.getClass().getMethod("setRealms", java.util.Collection.class)
                            .invoke(securityManager, existing);
                        log.info("GitHub OAuth realm registered via setRealms: {}", realm.getName());
                    } catch (ReflectiveOperationException e3) {
                        log.error("Could not register realm via reflection", e3);
                        registered = true;
                        return;
                    }
                }
            }
            log.info("GitHub OAuth realm registered successfully: {}", realm.getName());
            registered = true;
        } catch (Exception e) {
            log.error("Failed to register GitHub OAuth realm", e);
        }
    }
}
