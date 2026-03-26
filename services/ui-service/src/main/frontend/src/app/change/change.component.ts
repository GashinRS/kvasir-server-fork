import {} from '@angular/cdk';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, effect, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzDescriptionsModule } from 'ng-zorro-antd/descriptions';
import { NzEmptyModule } from 'ng-zorro-antd/empty';
import { NzFlexModule } from 'ng-zorro-antd/flex';
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
import {
  ChangeResultCode,
  ChangeStatusEntry,
  isPendingChangeRequest,
  PendingChangeRequest,
  ProcessedChange,
} from '../types';
import {
  ensureArray,
  mapToSignedN3Quads,
  SignedN3Quad,
  sortStatusEntries,
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
    NzButtonModule,
  ],
  templateUrl: './change.component.html',
  styleUrl: './change.component.less',
})
export class ChangeComponent {
  // DI
  private kvasir = inject(KvasirService);
  private modal = inject(NzModalService);

  // Input params
  readonly changeReportId = input.required<string>();

  change = rxResource<
    PendingChangeRequest | ProcessedChange,
    { changeReportId: string }
  >({
    params: () => ({ changeReportId: this.changeReportId() }),
    stream: ({ params: { changeReportId } }) =>
      this.kvasir.getProcessedChange(changeReportId),
  });

  readonly isPending = computed(
    () => this.change.hasValue() && isPendingChangeRequest(this.change.value()),
  );

  readonly pendingChange = computed<PendingChangeRequest | null>(() =>
    this.change.hasValue()
      ? (this.change.value() as PendingChangeRequest)
      : null,
  );
  readonly processedChange = computed<ProcessedChange | null>(() =>
    this.change.hasValue() ? (this.change.value() as ProcessedChange) : null,
  );

  statusEntry = computed<ChangeStatusEntry>(() => {
    if ((this.change.value() as any)['kss:podId'] != null) {
      const changeReport = this.change.value() as ProcessedChange;
      const entries = changeReport['kss:processingHistory'];
      if (Array.isArray(entries)) {
        return entries?.sort(sortStatusEntries('desc'))[0];
      } else {
        return entries as unknown as ChangeStatusEntry;
      }
    } else {
      return null as unknown as ChangeStatusEntry;
    }
  });

  records: SignedN3Quad[] = [];
  private cursor?: string;

  constructor() {
    effect(() => {
      if (this.processedChange() != null) {
        this.fetchPage();
      }
    });
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
      nzData:
        (this.change.value() as ProcessedChange)?.['kss:processingHistory'] ??
        [],
      nzWidth: '40%',
    });
  }

  reload(): void {
    this.records = [];
    this.cursor = undefined;
    this.change.reload();
  }

  private fetchPage(cursor?: string) {
    if (!this.isPending()) {
      const id = encodeURIComponent(this.processedChange()!['@id']!);
      this.kvasir
        .listChangeRecords(id, cursor)
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
}
