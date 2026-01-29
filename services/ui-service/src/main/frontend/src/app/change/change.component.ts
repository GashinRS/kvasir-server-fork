import { } from '@angular/cdk';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, OnInit } from '@angular/core';
import { rxResource, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { NzButtonComponent } from "ng-zorro-antd/button";
import { NzDescriptionsModule } from 'ng-zorro-antd/descriptions';
import { NzEmptyModule } from 'ng-zorro-antd/empty';
import { NzFlexModule } from "ng-zorro-antd/flex";
import { NzGridModule } from 'ng-zorro-antd/grid';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzModalService } from 'ng-zorro-antd/modal';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSpaceModule } from 'ng-zorro-antd/space';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { NzTypographyModule } from 'ng-zorro-antd/typography';
import { concatWith, from, map, switchMap } from 'rxjs';
import { LiteralBadgeComponent } from '../components/literal-badge/literal-badge.component';
import { StatusEntriesComponent } from '../modals/status-entries/status-entries.component';
import { RangeDiff, ServerPagedDirective } from '../server-paged.directive';
import { KvasirService } from '../services/kvasir.service';
import { ChangeReport, ChangeResultCode, ChangeStatusEntry } from '../types';
import {
  ensureArray,
  mapToSignedN3Quads,
  SignedN3Quad,
  sortStatusEntries
} from '../util/utils';

@Component({
  selector: 'app-change',
  imports: [
    NzDescriptionsModule,
    DatePipe,
    DecimalPipe,
    NzTableModule,
    NzSpaceModule,
    NzRadioModule,
    NzTypographyModule,
    NzTooltipModule,
    FormsModule,
    NzGridModule,
    ServerPagedDirective,
    NzEmptyModule,
    LiteralBadgeComponent,
    NzIconModule,
    NzFlexModule,
    NzButtonComponent,
],
  templateUrl: './change.component.html',
  styleUrl: './change.component.less',
})
export class ChangeComponent implements OnInit {
  // DI
  private kvasir = inject(KvasirService);
  private modal = inject(NzModalService)

  // Input params
  readonly changeReportId = input.required<string>();

  change = rxResource<ChangeReport, { changeReportId: string }>({
    params: () => ({ changeReportId: this.changeReportId() }),
    stream: ({ params: { changeReportId } }) => this.kvasir.getChangeReport(changeReportId),
  });

  statusEntry = computed<ChangeStatusEntry>(() => {
    const entries = this.change.value()?.['kss:statusEntry'];
    if (Array.isArray(entries)) {
      return entries?.sort(sortStatusEntries('desc'))[0];
    } else {
      return entries as unknown as ChangeStatusEntry;
    }
  });

  records: SignedN3Quad[] = [];
  private cursor?: string;

  ngOnInit(): void {
    this.fetchPage();
  }

  trackByIndex(_: number, data: any): number {
    return data;
  }

  onFetchNextPage(event: RangeDiff) {
    this.fetchPage(this.cursor);
  }

  isError(statusCode: ChangeResultCode) {
    switch (statusCode) {
      case ChangeResultCode.ASSERTION_FAILED:
      case ChangeResultCode.VALIDATION_ERROR:
      case ChangeResultCode.INTERNAL_ERROR:
        return false;
      default:
        return true;
    }
  }

  openStatusCodeHistory() {
    this.modal.info({
      nzTitle: 'StatusEntries',
      nzContent: StatusEntriesComponent,
      nzData: this.change.value()?.['kss:statusEntry'] ?? [],
      nzWidth: '40%',
    });
  }

  reload(): void {
   this.change.reload();
    this.records = [];
    this.cursor = undefined;
   this.fetchPage();
  }

  private fetchPage(cursor?: string) {
    this.kvasir
      .listChangeRecords(this.changeReportId()!, cursor)
      .pipe(
        map((page) => {
          this.cursor = page.cursor;
          const del = ensureArray(page.content['kss:delete'] ?? []);
          const ins = ensureArray(page.content['kss:insert'] ?? []);
          return {
            deletes: del,
            inserts: ins,
            context: page.content['@context'],
          };
        }),
        switchMap(({ deletes, inserts, context }) => {
          const delObs = from(mapToSignedN3Quads(deletes, context, '-'));
          const insObs = from(mapToSignedN3Quads(inserts, context, '+'));
          return delObs.pipe(concatWith(insObs));
        }),
      )
      .subscribe((quads) => {
        this.records = [...this.records, ...quads];
      });
  }
}
