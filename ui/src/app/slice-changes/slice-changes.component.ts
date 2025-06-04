import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzPageHeaderModule } from 'ng-zorro-antd/page-header';
import { NzTableModule } from 'ng-zorro-antd/table';
import { EMPTY, map } from 'rxjs';
import { KvasirService } from '../services/kvasir.service';
import { ChangeReport, ChangeStatusEntry, Slice } from '../types';
import { sortByTimestamp } from '../util/utils';

@Component({
  selector: 'app-slice-changes',
  imports: [
    NzPageHeaderModule,
    RouterLink,
    NzButtonModule,
    NzTableModule,
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

  slice = rxResource({
    loader: () => this.route.data.pipe(map(({ slice }) => slice as Slice)),
  });
  changes = rxResource({
    request: (): string | undefined =>
      this.slice.hasValue() ? this.slice.value()!['kss:name'] : undefined,
    loader: (params) =>
      params.request
        ? this.kvasir.listSliceChangeReports(params.request)
        : EMPTY,
  });

  lastStatus = (report: ChangeReport): ChangeStatusEntry | undefined =>
    report['kss:statusEntry'].sort(sortByTimestamp('desc'))?.at(0);

  openChangeReport(changeReportId: string): void {
    this.router.navigate([
      `/changes/view/`,
      encodeURIComponent(changeReportId),
    ]);
  }
}
