import { NavigationData } from '../../../shared/grid/navigation-data';
import { GridContextMenuButton } from '../../../shared/grid/grid-context-button/grid-context-button';
import { Component, OnInit } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { finalize } from 'rxjs/operators';
import { MainFilter } from '@shared/grid/main-filter';
import { DataCol } from '@shared/grid/data-col';
import { SixFiltersColums, SixFiltersMainFilters } from './six-filters.table-columns';
import { ReglissListApi } from '@core/services/auto-generated/regliss-list-api';
import { ActivatedRoute, Router } from '@angular/router';
import { FilterDto } from '@dto/FilterDto';
import { ToasterService } from '@services/toaster/toaster.service';
import { ViewMode } from '../watchlist-filters/view-mode/view-mode';
import { BreadcrumbService } from '@core/breadcrumb/breadcrumb.service';
import { ImportFileTypeEnum } from '@dto/ImportFileTypeEnum';
import { TranslateService } from '@ngx-translate/core';
import { SharedData } from '@lazy/shared.data';

@Component({
  selector: 'app-six-filters',
  templateUrl: './six-filters.component.html',
  styleUrls: ['./six-filters.component.scss']
})
export class SixFiltersComponent implements OnInit {

  allSixFilters: FilterDto[] = [];
  columns: DataCol[] = new SixFiltersColums().columns;
  contextMenuButtons: GridContextMenuButton[] = new SixFiltersColums().contextMenuButtons;
  mainFilters: MainFilter[] = new SixFiltersMainFilters().mainFilters;
  listId: number;
  viewMode: ViewMode;
  /** True while a regenerate request is running (the button is disabled). */
  regenerating = false;
  /** False until the back end confirms raw SIX instrument data exist (the button stays disabled). */
  regenerationAvailable = false;

  constructor(
    private listService: ReglissListApi,
    private route: ActivatedRoute,
    private router: Router,
    private breadcrumbService: BreadcrumbService,
    private sharedData: SharedData,
    private toaster: ToasterService,
  ) {
    if (this.viewMode === ViewMode.READ){
      this.breadcrumbService.setCanEditFilter(true);
    }

  }

  ngOnInit(): void {
    this.route.parent.params.subscribe(params => {
      this.listId = params['id']
    });

    if (this.listId) {
      this.getSixFilterList();
      this.checkRegenerationAvailable();
    }
  }

  addSixFilter(): void {
    this.router.navigate(['lists', this.listId, 'six-filters', 'add-filter']);
  }

  /**
   * Regenerate button: one request at a time (button disabled while it runs). The back end refuses it while a SIX
   * import is in progress or while a regeneration of this list is pending / running: the reason is shown.
   */
  triggerRegenerate() {
    if (this.regenerating) {
      return;
    }
    this.regenerating = true;
    this.listService.regenerateFile(this.listId)
      .pipe(finalize(() => this.regenerating = false))
      .subscribe({
        next: () => {
          this.toaster.success('success_messages.title', 'import-export.success_messages_regenerate.Regenerate_updated');
        },
        error: (error: HttpErrorResponse) => {
          this.toaster.error('import-export.error_messages_regenerate.title', this.regenerateErrorKey(error));
        }
      });
  }

  /** Enables the regenerate button only when the latest SIX instrument version has raw rows. */
  checkRegenerationAvailable() {
    this.listService.isSixRegenerationAvailable(this.listId).subscribe({
      next: available => this.regenerationAvailable = available === true,
      error: () => this.regenerationAvailable = false
    });
  }

  /** Translation key of the refusal reason (error code sent by the back end), or a generic one. */
  private regenerateErrorKey(error: HttpErrorResponse): string {
    const body = JSON.stringify(error && error.error ? error.error : (error ? error.message : ''));
    if (body.includes('SIX_NO_RAW_VERSION')) {
      return 'import-export.error_messages_regenerate.no_raw_version';
    }
    if (body.includes('SIX_IMPORT_IN_PROGRESS')) {
      return 'import-export.error_messages_regenerate.import_in_progress';
    }
    if (body.includes('SIX_REGENERATION_IN_PROGRESS')) {
      return 'import-export.error_messages_regenerate.regeneration_in_progress';
    }
    return 'import-export.error_messages_regenerate.failed';
  }

  getSixFilterList() {
    this.listService.getSixFilters(this.listId).subscribe(res => {
      this.allSixFilters = Object.values(res);
    })
  }

  navigateToUrl(navigationData: NavigationData<FilterDto>) {
    this.router.navigate(['lists', this.listId, 'six-filters', navigationData.row.id], {
      state: {
        viewMode: ViewMode.EDIT
      }
    });
  }

  deleteRow(row: any) {
    this.listService.deleteSixFilter(this.listId, row.id).subscribe(() => {
      this.getSixFilterList();
    });
  }

  changeStatusFilter(row: any) {
    if (row.isActive) {
      this.listService.deactivateSixFilter(row.id, this.listId).subscribe(() => {
        this.getSixFilterList();
      })
    }
    else {
      this.listService.activateSixFilter(row.id, this.listId).subscribe(() => {
        this.getSixFilterList();
      })
    }
  }

  goToSixFilterComponent(row: any) {
    this.router.navigate(['lists', this.listId, 'six-filters', row.id], {
      state: {
        viewMode: ViewMode.READ
      }
    });
  }

  changedViewMode() {
    this.sharedData.changedFilterViewMode.subscribe(
      viewMode => {
        this.viewMode = viewMode;
      }
    );
  }
}
