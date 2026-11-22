package com.foxcash.infrastructure.seed;

import com.foxcash.domain.model.community.CommunityPost;
import com.foxcash.domain.port.out.CommunityPostRepository;
import com.foxcash.domain.port.out.MasterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * Seed do post inicial da Comunidade (Spec 119 D5 — opcional, idempotente):
 * cria o post "Bem-vindo à Comunidade" apenas se (a) existir um master no banco
 * (autor obrigatório — FK {@code published_by}) e (b) ainda não existir post com
 * o mesmo título. Sem master cadastrado → não cria nem falha (log informativo).
 */
@Component
public class CommunityPostSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CommunityPostSeedRunner.class);

    static final String WELCOME_TITLE = "Bem-vindo à Comunidade FoxCash";

    private final CommunityPostRepository postRepository;
    private final MasterRepository masterRepository;
    private final Clock clock;

    public CommunityPostSeedRunner(CommunityPostRepository postRepository,
                                   MasterRepository masterRepository,
                                   Clock clock) {
        this.postRepository = postRepository;
        this.masterRepository = masterRepository;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (postRepository.existsByTitle(WELCOME_TITLE)) {
            return;
        }
        UUID masterId = masterRepository.findAll().stream()
                .filter(master -> master.isActive())
                .map(master -> master.id())
                .findFirst()
                .orElse(null);
        if (masterId == null) {
            log.info("Seed da Comunidade ignorado: sem master ativo cadastrado (autor obrigatorio).");
            return;
        }
        postRepository.save(CommunityPost.create(
                WELCOME_TITLE,
                "Acompanhe aqui os avisos e novidades da plataforma. Este é o canal oficial de comunicados.",
                masterId, true, clock.instant()));
        log.info("Seed da Comunidade: post inicial criado (\"Bem-vindo à Comunidade FoxCash\").");
    }
}
