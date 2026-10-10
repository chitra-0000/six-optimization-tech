package com.bnpp.regliss.repository.batch;


public interface ListImportNodeLockRepository extends EntityRepository< ListImportNodeLock, Long>{

    ListImportNodeLock findByListAndNodeName(ReglissList list, String nodeName);
    ListImportNodeLock findByListIdAndNodeName(Long listId, String nodeName);

}
