    // ==== ReglissListController.java - ONLY lines ~99-139 shown in the screenshot ====

    public List<VersionForVersionPageDto> getVersionsForVersionPage(@PathVariable Long id){   // TODO: annotation on line 98 is off-screen
        listPermissions.checkListAccess(id);
        return listFacade.getVersionsForVersionPage(id);
    }

    @GetMapping("/breadcrumb-for-list/{id}")
    public ListBreadcrumbDto getBreadcrumbForList(@PathVariable Long id){
        listPermissions.checkListAccess(id);
        return listFacade.getBreadcrumbForList(id);
    }

    @PostMapping("/{listId}/six-filters/regenerate")
    public void regenerateFile(@PathVariable Long listId) {
        listPermissions.checkListAccess(listId);
        listFacade.regenerateFile(listId);
    }

    @SecuredFeature(FeaturePermission.listCreate)
    @PostMapping
    public SingleValue create(@RequestBody ReglissListDto dto) {
        long newId = listFacade.create(dto);
        return new SingleValue(newId);
    }

    @SecuredFeature(FeaturePermission.listEdit)
    @PutMapping
    public void update(@RequestBody ReglissListDto dto) {
        listPermissions.checkListAccess(dto.id);
        listFacade.update(dto);
    }

    @GetMapping("/{id}/broadcast-list")
    public BroadcastDto getBroadcastEmails(@PathVariable Long id){
        listPermissions.checkListAccess(id);
        return listFacade.getBroadcastEmails(id);
    }

    @SecuredFeature(FeaturePermission.listBroadcastListEdit)
    @PutMapping("/{id}/broadcast-list")
    public void updateBroadcastEmails(@PathVariable Long id, @RequestBody BroadcastDto broadcastDto) {
        listPermissions.checkListAccess(id);
        // ... rest of the method is off-screen
