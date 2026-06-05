import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzPageHeaderModule } from 'ng-zorro-antd/page-header';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { EMPTY, map } from 'rxjs';
import { KvasirService } from '../services/kvasir.service';
import { ChangeStatusEntry, ProcessedChange, Slice } from '../types';
import { sortByTimestamp } from '../util/utils';

@Component({
  selector: 'app-slice-changes',
  imports: [
    NzPageHeaderModule,
    NzButtonModule,
    NzTableModule,
    NzTagModule,
    DecimalPipe,
    DatePipe,
  ],
  templateUrl: './slice-changes.component.html',
  styleUrl: './slice-changes.component.less',
})
export class SliceChangesComponent {
  private route = inject(ActivatedRoute);
  private kvasir = inject(KvasirService);
  private router = inject(Router);

  tag = computed<string | null>(
    () => this.route.snapshot.queryParamMap.get('tag') ?? null,
  );

  slice = rxResource({
    stream: () => this.route.data.pipe(map(({ slice }) => slice as Slice)),
  });
  changes = rxResource({
    params: (): string | undefined =>
      this.slice.hasValue() ? this.slice.value()!['kss:name'] : undefined,
    stream: ({ params }) =>
      params ? this.kvasir.listSliceChangeReports(params, this.tag()) : EMPTY,
  });

  lastStatus = (report: ProcessedChange): ChangeStatusEntry | undefined =>
    report['kss:processingHistory'].sort(sortByTimestamp('desc'))?.at(0);

  openChangeReport(changeReportId: string): void {
    this.router.navigate([
      `/changes/view/`,
      encodeURIComponent(changeReportId),
    ]);
  }

  goToCreateChange(): void {
    this.router.navigate(['./new'], {
      relativeTo: this.route,
      queryParams: { tag: this.tag() ?? undefined },
    });
  }
}
