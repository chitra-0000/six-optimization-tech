package com.bnpp.regliss.repository.batch;

import java.util.Collection;


public interface ListImportNodeLockRepository extends EntityRepository< ListImportNodeLock, Long>{

    ListImportNodeLock findByListAndNodeName(ReglissList list, String nodeName);
    ListImportNodeLock findByListIdAndNodeName(Long listId, String nodeName);

    /** SIX regeneration (refused while a SIX import runs): number of import locks held on these lists, any server. */
    long countByListIdIn(Collection<Long> listIds);

}
