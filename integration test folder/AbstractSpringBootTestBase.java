package com.bnpp.regliss.repository;

// TODO: imports (lines 3-31) were folded in the IDE; reconstructed from usage.
//       Verify packages of project classes (TestConfig, ReglissProfile, repositories, User, UserBuilder, Language,
//       ReglissRequestContext, StaticContextAccessor, TestTransaction source).
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootContextLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;          // jakarta.persistence.* on Spring Boot 3
import javax.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.concurrent.Callable;

@SpringBootTest
@ContextConfiguration(classes = TestConfig.class, loader = SpringBootContextLoader.class)
@ActiveProfiles(profiles = {"test", ReglissProfile.REGLISS_BATCH})
@RunWith(SpringRunner.class)
@Slf4j
public abstract class AbstractSpringBootTestBase {

    @Autowired
    protected AliasRepository aliasRepo;

    @Autowired
    protected ImportedFileRepository importedFileRepo;

    @Autowired
    protected VersionRepository versionRepo;

    @Autowired
    protected GeneratedFileRepository generatedFileRepository;

    @Autowired
    protected  FileDeliveryRepository fileDeliveryRepository;

    @Autowired
    protected SubscriptionRepository subscriptionRepository;

    @Autowired
    protected SubscriberRepository subscriberRepository;

    @Autowired
    protected ReglissListRepository listRepo;

    @Autowired
    protected ConcatenatedListRepository cListRepo;

    @Autowired
    protected RecordRepository recordRepo;

    @Autowired
    protected AuditRepository auditRepo;

    @Autowired
    protected BatchExportRepository batchExportRepository;
    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected DJExportFilterRepository djExportFilterRepository;

    @Autowired
    protected EmailTemplateRepository emailTemplateRepository;

    @PersistenceContext
    protected EntityManager em;

    @Autowired
    protected ReglissRequestContext requestContext;

    @Autowired
    protected ApplicationContext testContext;

    @Autowired
    protected TransactionRunner txRunner;

    @Autowired
    private CacheManager cacheManager;
    @Autowired
    protected EmailTemplateRepository emailTemplateRepo;

    protected User me;
    protected User anotherUser;

    protected final LocalDateTime requestTime = LocalDateTime.now();


    public void evictReferentialThreadCache() {
        log.info("evicting thread cache to clear referential cache for tests...");
        cacheManager.getCache("threadCache").clear();
    }

    @Before
    public void setStaticSpringInstance() {
        evictReferentialThreadCache();
        StaticContextAccessor.getInstance().setApplicationContext__FromTests(testContext);
        me = new UserBuilder().withLanguage(Language.FR).build();
        anotherUser = new UserBuilder().build();
    }

    @Component
    public static class TransactionRunner {
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void runInTransaction(Runnable action) { action.run(); }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public <T> T callInTransaction(Callable<T> action) {
            try {
                return action.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }


    protected static void commitAndRestartTransaction() {
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();
    }

    protected void cleanRepos() {
        em.flush();
//        recordRepo.findAll().forEach(r->r.getDescriptions3().clear());
        recordRepo.findAll().forEach(r->r.setDescriptions3(new HashSet<>()));
        em.createNativeQuery("DELETE RECORD_DOW_JONES_DESCRIPTION").executeUpdate();

        aliasRepo.deleteAll();
        recordRepo.deleteAll();
        //userRepository;//cannot delete users as some are used throughout tests
        batchExportRepository.deleteAll();

        fileDeliveryRepository.deleteAll();


        subscriberRepository.findAll().forEach(s->s.getSubscriptions().forEach(s::removeSubscription));
        subscriptionRepository.findAll().forEach(s->s.getGeneratedFileFormat().setFilter(null));
        subscriptionRepository.deleteAll();
        generatedFileRepository.deleteAll();
        djExportFilterRepository.deleteAll();

        subscriberRepository.deleteAll();

        auditRepo.deleteAll();


        importedFileRepo.deleteAll();
        versionRepo.deleteAll();
        listRepo.deleteAll();
        emailTemplateRepository.deleteAll();
        em.flush();
    }
}
