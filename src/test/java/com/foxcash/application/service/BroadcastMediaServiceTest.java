package com.foxcash.application.service;

import com.foxcash.domain.exception.StoreBotNotFoundException;
import com.foxcash.domain.model.broadcast.BroadcastMediaType;
import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.out.DiscordBotApi;
import com.foxcash.domain.port.out.StoreBotRepository;
import com.foxcash.domain.port.out.TelegramBotApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do upload de mídia do broadcast (Spec 79/D7): limites por tipo (imagem
 * ≤10MB; vídeo/áudio ≤50MB → 400), mime/tipo inválido, reuso do file_id do
 * Telegram (canal CDN) e 404 cross-lojista.
 */
class BroadcastMediaServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID BOT_ID = UUID.randomUUID();

    private final StoreBotRepository botRepository = mock(StoreBotRepository.class);
    private final TelegramBotApi telegramApi = mock(TelegramBotApi.class);
    private final DiscordBotApi discordApi = mock(DiscordBotApi.class);

    private BroadcastMediaService service;

    @BeforeEach
    void setUp() {
        service = new BroadcastMediaService(botRepository, telegramApi, discordApi);
    }

    private StoreBot telegramBot() {
        return StoreBot.restore(BOT_ID, MERCHANT_ID, BotPlatform.TELEGRAM,
                "123456:ABC-secret-token", "hash", "wh-hash", "Bot Vendas", "vendasbot", null,
                BotStatus.ACTIVE, null, NOW, null, NOW, NOW);
    }

    @Test
    void telegramUploadReusesFileIdFromCdn() {
        when(botRepository.findByIdAndMerchantId(BOT_ID, MERCHANT_ID))
                .thenReturn(Optional.of(telegramBot()));
        when(telegramApi.sendMedia("123456:ABC-secret-token", "IMAGE",
                new byte[]{1, 2, 3}, "banner.jpg")).thenReturn("AgAC-AgADfileid");

        BroadcastMediaService.MediaUploadResult result =
                service.upload(MERCHANT_ID, BOT_ID, "image", new byte[]{1, 2, 3}, "banner.jpg");

        assertThat(result.mediaFileId()).isEqualTo("AgAC-AgADfileid");
        assertThat(result.mediaType()).isEqualTo(BroadcastMediaType.IMAGE);
        verify(telegramApi).sendMedia("123456:ABC-secret-token", "IMAGE",
                new byte[]{1, 2, 3}, "banner.jpg");
    }

    @Test
    void imageOver10MbRejected() {
        when(botRepository.findByIdAndMerchantId(BOT_ID, MERCHANT_ID))
                .thenReturn(Optional.of(telegramBot()));
        byte[] big = new byte[10 * 1024 * 1024 + 1];

        assertThatThrownBy(() -> service.upload(MERCHANT_ID, BOT_ID, "image", big, "big.jpg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10MB");
    }

    @Test
    void videoOver50MbRejected() {
        when(botRepository.findByIdAndMerchantId(BOT_ID, MERCHANT_ID))
                .thenReturn(Optional.of(telegramBot()));
        byte[] big = new byte[50 * 1024 * 1024 + 1];

        assertThatThrownBy(() -> service.upload(MERCHANT_ID, BOT_ID, "video", big, "big.mp4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("50MB");
    }

    @Test
    void videoAtExactly50MbAccepted() {
        when(botRepository.findByIdAndMerchantId(BOT_ID, MERCHANT_ID))
                .thenReturn(Optional.of(telegramBot()));
        when(telegramApi.sendMedia(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("fileid");

        BroadcastMediaService.MediaUploadResult result =
                service.upload(MERCHANT_ID, BOT_ID, "video", new byte[50 * 1024 * 1024], "v.mp4");

        assertThat(result.mediaType()).isEqualTo(BroadcastMediaType.VIDEO);
    }

    @Test
    void invalidMediaTypeRejected() {
        when(botRepository.findByIdAndMerchantId(BOT_ID, MERCHANT_ID))
                .thenReturn(Optional.of(telegramBot()));

        assertThatThrownBy(() -> service.upload(MERCHANT_ID, BOT_ID, "gif", new byte[]{1}, "x.gif"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("media_type");
    }

    @Test
    void emptyFileRejected() {
        when(botRepository.findByIdAndMerchantId(BOT_ID, MERCHANT_ID))
                .thenReturn(Optional.of(telegramBot()));

        assertThatThrownBy(() -> service.upload(MERCHANT_ID, BOT_ID, "image", new byte[0], "vazio.jpg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vazio");
    }

    @Test
    void botOfAnotherMerchantThrows404() {
        when(botRepository.findByIdAndMerchantId(BOT_ID, MERCHANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload(MERCHANT_ID, BOT_ID, "image", new byte[]{1}, "x.jpg"))
                .isInstanceOf(StoreBotNotFoundException.class);
    }
}
