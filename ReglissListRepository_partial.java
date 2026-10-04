    // ==== ReglissListRepository.java - ONLY the last part was in the screenshot (package, imports and earlier methods are missing) ====
    // Paste these three methods at the end of the existing interface, before its closing brace.

    @Query("SELECT list from ReglissList list "
            + "WHERE reference IN ?1 "
            + "AND  deleted = false "
            + "AND  active = true")
    List<ReglissList> findByListRef(List<String> ref);

    @Query("SELECT list FROM ReglissList list "
            + "WHERE list.importConfiguration.importFileType IN ('SIX_STRUCTURED_FILE','SIX_INSTRUMENTS_FILE','SIX_OPTIONS_FILE') "
            + "AND list.deleted = false "
            + "AND list.active = true "
            + "AND list.id != ?1 "
            + "AND (SELECT COUNT(v) FROM Version v WHERE v.list.id = list.id) > 0 ")
    List<ReglissList> getAllSixLists(Long id);

    @Query("SELECT list from ReglissList list "
            + "WHERE importConfiguration.importFileType = ?1 "
            + "AND  deleted = false "
            + "AND  importConfiguration.allowAutoUpload = true "
            + "AND  active = true")
    List<ReglissList> findByDJFormatNotDeleted(ImportFileType format);
}
