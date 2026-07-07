package org.sonatype.nexus.plugins.githuboauth;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authc.pam.UnsupportedTokenException;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.sonatype.nexus.common.Description;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Nexus security realm that authenticates users against the GitHub (or GitHub Enterprise)
 * API using a personal access token as the password.
 *
 * <p>Registered as a Spring component so the Nexus 3.71+ Spring runtime discovers it via
 * the realm qualifier map and exposes it on the Administration &rarr; Security &rarr; Realms
 * page.</p>
 */
@Component
@Qualifier(GithubOauthAuthenticatingRealm.NAME)
@Description("Github Enterprise Authentication Realm")
public class GithubOauthAuthenticatingRealm extends AuthorizingRealm {
    private static final Logger LOGGER = LoggerFactory.getLogger(GithubOauthAuthenticatingRealm.class);

    public static final String NAME = "GithubOauthAuthenticatingRealm";

    private final GithubApiClient githubClient;

    @Autowired
    public GithubOauthAuthenticatingRealm(final GithubApiClient githubClient) {
        this.githubClient = githubClient;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    protected void onInit() {
        super.onInit();
        LOGGER.info("Github oAuth Realm initialized");
    }

    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(final PrincipalCollection principals) {
        GithubPrincipal user = (GithubPrincipal) principals.getPrimaryPrincipal();
        LOGGER.info("doGetAuthorizationInfo for user {} with roles {}",
                user.getUsername(),
                user.getRoles().stream().collect(Collectors.joining(", ")));
        return new SimpleAuthorizationInfo(user.getRoles());
    }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(final AuthenticationToken token) throws AuthenticationException {
        if (!(token instanceof UsernamePasswordToken)) {
            throw new UnsupportedTokenException(String.format(
                    "Token of type %s  is not supported. A %s is required.",
                    token.getClass().getName(), UsernamePasswordToken.class.getName()));
        }

        UsernamePasswordToken t = (UsernamePasswordToken) token;
        LOGGER.info("doGetAuthenticationInfo for {}", t.getUsername());
        GithubPrincipal authenticatedPrincipal;
        try {
            authenticatedPrincipal = githubClient.authz(t.getUsername(), t.getPassword());
            LOGGER.info("Successfully authenticated {}", t.getUsername());
        } catch (GithubAuthenticationException e) {
            LOGGER.warn("Failed authentication", e);
            return null;
        }

        return createSimpleAuthInfo(authenticatedPrincipal, t);
    }

    private SimpleAuthenticationInfo createSimpleAuthInfo(final GithubPrincipal principal, final UsernamePasswordToken token) {
        return new SimpleAuthenticationInfo(principal, token.getCredentials(), NAME);
    }
}
