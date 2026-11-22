package com.foxcash.infrastructure.seed;

import com.foxcash.domain.model.master.Master;
import com.foxcash.domain.port.out.MasterRepository;
import com.foxcash.domain.port.out.PasswordHasher;
import com.foxcash.infrastructure.config.MasterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Seed do primeiro perfil master (Spec 13).
 *
 * <p>No boot, se a tabela {@code masters} estiver vazia e as env vars
 * {@code FOXCASH_MASTER_SEED_USERNAME} / {@code FOXCASH_MASTER_SEED_PASSWORD} estiverem
 * preenchidas, cria o master inicial (senha com hash BCrypt). Sem env vars
 * (caso de prod sem seed configurado) <b>não cria nem falha</b> — apenas
 * documenta em log. Com a tabela já populada, não duplica.
 */
@Component
public class MasterSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MasterSeedRunner.class);

    private final MasterRepository masterRepository;
    private final PasswordHasher passwordHasher;
    private final MasterProperties properties;

    public MasterSeedRunner(MasterRepository masterRepository,
                            PasswordHasher passwordHasher,
                            MasterProperties properties) {
        this.masterRepository = masterRepository;
        this.passwordHasher = passwordHasher;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (masterRepository.count() > 0) {
            log.info("Seed do master ignorado: ja existe perfil master cadastrado.");
            return;
        }

        String username = properties.getSeedUsername();
        String password = properties.getSeedPassword();

        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            log.warn("Seed do master nao configurado (FOXCASH_MASTER_SEED_USERNAME/FOXCASH_MASTER_SEED_PASSWORD "
                    + "ausentes ou vazios). Nenhum master inicial foi criado — crie o perfil manualmente.");
            return;
        }

        Master master = Master.create(
                username.trim(),
                passwordHasher.hash(password),
                properties.getSeedName(),
                true); // Spec 41: o primeiro master é o ADMIN (can_manage_masters)
        masterRepository.save(master);
        log.info("Master inicial criado com sucesso (admin): username={}", master.username());
    }
}
