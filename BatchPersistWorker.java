package com.bnpp.regliss.importer.six.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class BatchPersistWorker {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private ApplicationContext applicationContext;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T extends PersistableEntity> void persistBatchRaw(List<T> files, Version version, ReglissList list) {
        try {
            for (T file : files) {
                file.setVersion(version);
                file.setList(list);
                entityManager.persist(file);
            }
            entityManager.flush();
            entityManager.clear();

        } catch (Exception e) {
            throw new ReglissException(e, "Error persisting batch");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> void persistBatchFiltered(List<T> batchToProcess) {
        try {
            for (T file : batchToProcess) {
                entityManager.persist(file);
            }
            entityManager.flush();
            entityManager.clear();

        } catch (Exception e) {
            throw new ReglissException(e, "Error persisting batch");
        }
    }
}
