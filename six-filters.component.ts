import { NavigationData } from '../../../shared/grid/navigation-data';
import { GridContextMenuButton } from '../../../shared/grid/grid-context-button/grid-context-button';
import { Component, OnInit } from '@angular/core';
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
    }
  }

  addSixFilter(): void {
    this.router.navigate(['lists', this.listId, 'six-filters', 'add-filter']);
  }

  triggerRegenerate() {
    this.listService.regenerateFile(this.listId).subscribe(res => {
        this.toaster.success("success_messages.title", 'import-export.success_messages_regenerate.Regenerate_updated');
      })
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
