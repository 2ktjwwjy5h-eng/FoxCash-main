package com.foxcash.domain.exception;

/**
 * Post da Comunidade inexistente — 404 {@code community_post_not_found}.
 */
public class CommunityPostNotFoundException extends RuntimeException {

    public CommunityPostNotFoundException() {
        super("Post da comunidade nao encontrado");
    }
}
