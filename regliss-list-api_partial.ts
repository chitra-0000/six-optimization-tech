  // ==== regliss-list-api.ts (class ReglissListApi) - ONLY lines ~339-385 shown in the screenshot ====

  checkIfCRBListExists(id:number): Observable<SingleValue> {
    return this.http.get<SingleValue>(`${environment.baseUrl}/lists/${id}/number-of-crb-list-db`);   // TODO: line cut off at the top of the photo
  }

  getSixFilter(filterId: number, id: number): Observable<SixFiltersAndSubFiltersDto> {
    return this.http.get<SixFiltersAndSubFiltersDto>(`${environment.baseUrl}/lists/${id}/six-filters/${filterId}`,{})
      .pipe(map(response => Object.assign(new SixFiltersAndSubFiltersDto(), response)))
  }

  getSixFilters(id: number): Observable<SixFiltersDto> {
    return this.http.get<SixFiltersDto>(`${environment.baseUrl}/lists/${id}/six-filters`,{})
      .pipe(map(response => Object.assign(new SixFiltersDto(), response)))
  }

  regenerateFile(listId: number): Observable<void> {
    return this.http.post<void>(`${environment.baseUrl}/lists/${listId}/six-filters/regenerate`,{})
  }

  createSixFilter(dto:SixFiltersDto, listId:number): Observable<SingleValue> {
    return this.http.post<SingleValue>(`${environment.baseUrl}/lists/${listId}/six-filters`,dto, {}).pipe(map(response => Object.assign(new SingleValue(), response)));
  }

  updateSixFilter(dto: SixFiltersDto, listId: number, filterId: number): Observable<void> {
    return this.http.put<void>(`${environment.baseUrl}/lists/${listId}/six-filters/${filterId}`,dto,{})
  }

  deleteSixFilter(listId:number, filterId:number) : Observable<void>{
    return this.http.delete<void>(`${environment.baseUrl}/lists/${listId}/six-filters/${filterId}`)
  }

  activateSixFilter(filterId: number, listId: number): Observable<void> {
    return this.http.put<void>(`${environment.baseUrl}/lists/${listId}/six-filters/${filterId}/activate`,{},{})
  }

  deactivateSixFilter(filterId: number, listId: number): Observable<void> {
    return this.http.put<void>(`${environment.baseUrl}/lists/${listId}/six-filters/${filterId}/deactivate`,{},{})
  }

  getSixSubfilter(listId: number, filterId: number, subfilterId: number): Observable<SixSubFiltersDto> {
    return this.http.get<SixSubFiltersDto>(`${environment.baseUrl}/lists/${listId}/six-filters/${filterId}/subfilters/${subfilterId}`,{})
      .pipe(map(response => Object.assign(new SixSubFiltersDto(), response)))
  }

  createSixSubfilter(dtos: SixSubFiltersDto, listId: number, filterId: number): Observable<void> {
    return this.http.post<void>(`${environment.baseUrl}/lists/${listId}/six-filters/${filterId}/subfilters`,dtos,{});
  }

  // ... updateSixSubfilter(...) and the rest of the class are off-screen
