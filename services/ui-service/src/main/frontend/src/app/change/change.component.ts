import {} from '@angular/cdk';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, OnInit } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { NzDescriptionsModule } from 'ng-zorro-antd/descriptions';
import { NzEmptyModule } from 'ng-zorro-antd/empty';
import { NzGridModule } from 'ng-zorro-antd/grid';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSpaceModule } from 'ng-zorro-antd/space';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTypographyModule } from 'ng-zorro-antd/typography';
import { concatWith, from, map, switchMap } from 'rxjs';
import { LiteralBadgeComponent } from '../components/literal-badge/literal-badge.component';
import { RangeDiff, ServerPagedDirective } from '../server-paged.directive';
import { KvasirService } from '../services/kvasir.service';
import { ChangeReport, ChangeResultCode } from '../types';
import {
  ensureArray,
  mapToSignedN3Quads,
  SignedN3Quad,
  sortByTimestamp,
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
    FormsModule,
    NzGridModule,
    ServerPagedDirective,
    RouterModule,
    NzEmptyModule,
    LiteralBadgeComponent,
  ],
  templateUrl: './change.component.html',
  styleUrl: './change.component.less',
})
export class ChangeComponent implements OnInit {
  // DI
  private route = inject(ActivatedRoute);
  private kvasir = inject(KvasirService);

  // Input params
  readonly changeReportId = input.required<string>();

  change = rxResource<ChangeReport, unknown>({
    stream: () => this.route.data.pipe(map(({ changeReport }) => changeReport)),
  });

  statusEntry = computed<any>(() => {
    const entries = this.change.value()?.['kss:statusEntry'];
    return entries?.sort(sortByTimestamp('desc'))[0];
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
