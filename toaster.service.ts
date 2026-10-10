import { Injectable } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { MessageService } from 'primeng/api';

@Injectable({
  providedIn: 'root'
})
export class ToasterService {

  constructor(
    private messageService: MessageService,
    private translationService: TranslateService
    ) { }

    public success(summary: string, detail: string, life: number = 5000) {
    this.messageService.add({
      severity: 'success',
      summary: this.translationService.instant(summary),
      detail: this.translationService.instant(detail),
      life: life,
      closable: true
    })
  }

   public error(
     summary: string, detail: string, sticky: boolean = true, life: number = 15000) {
     this.messageService.add({
      severity: 'error',
      summary: this.translationService.instant(summary),
      detail: this.translationService.instant(detail),
      life: life,
      closable: true,
      sticky: sticky
    })
  }
  //to be deleted, only for testing purposes
  public devError(
    summary: string, detail: string, sticky: boolean = true) {
  }
}
