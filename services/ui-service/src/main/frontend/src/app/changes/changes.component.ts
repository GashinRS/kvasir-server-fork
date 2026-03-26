import { DatePipe, DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterModule } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzPageHeaderModule } from 'ng-zorro-antd/page-header';
import { NzTableModule } from 'ng-zorro-antd/table';
import { KvasirService } from '../services/kvasir.service';
import { ProcessedChange, ChangeStatusEntry } from '../types';
import { sortByTimestamp } from '../util/utils';

@Component({
  selector: 'app-changes',
  imports: [
    NzButtonModule,
    NzTableModule,
    NzPageHeaderModule,
    RouterModule,
    DecimalPipe,
    DatePipe,
  ],
  templateUrl: './changes.component.html',
  styleUrl: './changes.component.less',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChangesComponent {
  changes = rxResource({
    stream: () => this.kvasir.listChangeReports(),
  });

  // DI
  private kvasir = inject(KvasirService);
  private router = inject(Router);

  lastStatus = (report: ProcessedChange): ChangeStatusEntry | undefined =>
    report['kss:processingHistory'].sort(sortByTimestamp('desc'))?.at(0);

  openChangeReport(changeReportId: string): void {
    this.router.navigate([
      '/changes/view/',
      encodeURIComponent(changeReportId),
    ]);
  }
}
