    // ==== ReglissListFacade.java - ONLY lines 204-239 shown in the screenshot ====

    public List<VersionForVersionPageDto> getVersionsForVersionPage(Long listId){
        List<ListVersionVO> list = versionRepo.getAllVersionForVersionPageByListId(listId);
        ReglissList listOfVersions = listRepo.getExactlyOne(listId);
        return list.stream().map(v -> versionMapper.toVersionForVersionPageDto(v, getNumberOfMinorUpdates(v, listOfVersions.getImportFileType()), listOfVersions))
                .sorted(comparing(VersionForVersionPageDto::getVersionNumber).reversed())
                .collect(Collectors.toList());
    }

    public void regenerateFile(Long listId) {
        batchExportService.persistSixFilteredFileGenerationStubInTx(listId);
    }

    private Long getNumberOfMinorUpdates(ListVersionVO vo, ImportFileType importFileType){
        if(importFileType.isWatchlistFormat()) {
            return 0L;
        }
        return auditRepo.countAudits4MinorUpdates(vo.getId());
    }

    public ListBreadcrumbDto getBreadcrumbForList(Long listId){
        ListBreadcrumbVO list=listRepo.getListBreadcrumb(listId);
        ListBreadcrumbDto dto=mapper.toListBreadcrumbDto(list);
        List<UserListPermission> allPermissionsForList = userPermissionsRepo.findByUserAndReglissListId(requestContext.getCurrentUser(), listId);
        if(!CollectionUtils.isEmpty(allPermissionsForList)) {
            dto.canDownloadExport = allPermissionsForList.stream().filter(p -> p.getPermission().equals(ListPermission.DOWNLOADEXPORT)).count() > 0;
            dto.canDownloadImport = allPermissionsForList.stream().filter(p -> p.getPermission().equals(ListPermission.DOWNLOADIMPORT)).count() > 0;
        }

        return dto;
    }

    public List<ReglissListShortNameDto> getAllListShortNamesAndIds() {
        List<ListShortNameDto> listShortNames = listRepo.getAllListShortNames();
        return listShortNames.stream().map(listShortNameDto -> new ReglissListShortNameDto(listShortNameDto.getReference() + "-" + listShortNameDto.getShortName(),
                listShortNameDto.getId())).collect(Collectors.toList());
    }
