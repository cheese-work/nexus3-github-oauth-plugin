package org.sonatype.nexus.plugins.githuboauth;

public class GithubAuthenticationException extends Exception{
    public GithubAuthenticationException(String message){
        super(message);
    }

    public GithubAuthenticationException(Throwable cause){
        super(cause);
    }

}
