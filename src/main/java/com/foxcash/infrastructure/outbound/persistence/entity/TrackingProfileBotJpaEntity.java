package com.foxcash.infrastructure.outbound.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA do vínculo perfil de tracking ↔ bot (Spec 81/D2.1) — chave
 * composta {@code (profile_id, bot_id)}.
 */
@Entity
@Table(name = "tracking_profile_bots")
public class TrackingProfileBotJpaEntity {

    @EmbeddedId
    private TrackingProfileBotId id;

    protected TrackingProfileBotJpaEntity() {
    }

    public TrackingProfileBotJpaEntity(UUID profileId, UUID botId) {
        this.id = new TrackingProfileBotId(profileId, botId);
    }

    public TrackingProfileBotId getId() { return id; }

    @Embeddable
    public static class TrackingProfileBotId implements Serializable {

        @Column(name = "profile_id")
        private UUID profileId;

        @Column(name = "bot_id")
        private UUID botId;

        protected TrackingProfileBotId() {
        }

        public TrackingProfileBotId(UUID profileId, UUID botId) {
            this.profileId = profileId;
            this.botId = botId;
        }

        public UUID getProfileId() { return profileId; }
        public UUID getBotId() { return botId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TrackingProfileBotId that)) return false;
            return Objects.equals(profileId, that.profileId) && Objects.equals(botId, that.botId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(profileId, botId);
        }
    }
}
