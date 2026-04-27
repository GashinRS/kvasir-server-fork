import { Clipboard } from '@angular/cdk/clipboard';
import {
  Component,
  computed,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCollapseModule } from 'ng-zorro-antd/collapse';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzListModule } from 'ng-zorro-antd/list';
import { NzPageHeaderModule } from 'ng-zorro-antd/page-header';
import { NzPopoverModule } from 'ng-zorro-antd/popover';
import { NzSpaceModule } from 'ng-zorro-antd/space';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';

import { ScrollingModule } from '@angular/cdk/scrolling';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzModalService } from 'ng-zorro-antd/modal';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { NzTypographyModule } from 'ng-zorro-antd/typography';
import { SliceLineageComponent } from '../components/slice-lineage/slice-lineage.component';
import { KvasirService } from '../services/kvasir.service';
import { SliceSummary } from '../types';
import { ensureArray } from '../util/utils';
import { NzEmptyModule } from 'ng-zorro-antd/empty';

@Component({
  selector: 'app-slices',
  standalone: true,
  imports: [
    NzPageHeaderModule,
    NzTableModule,
    NzButtonModule,
    NzSpaceModule,
    NzIconModule,
    NzTooltipModule,
    NzPopoverModule,
    NzTypographyModule,
    NzCollapseModule,
    NzTagModule,
    NzListModule,
    NzInputModule,
    NzEmptyModule,
    FormsModule,
    RouterLink,
    NgTemplateOutlet,
    ScrollingModule,
    SliceLineageComponent,
  ],
  templateUrl: './slices.component.html',
  styleUrl: './slices.component.less',
  encapsulation: ViewEncapsulation.None,
})
export class SlicesComponent {
  // DI
  private kvasir = inject(KvasirService);
  private router = inject(Router);
  private clipboard = inject(Clipboard);
  private message = inject(NzMessageService);
  private modal = inject(NzModalService);

  slices = rxResource({
    stream: () => this.kvasir.listSlices(),
  });

  filter = signal<string>('');

  filteredSlices = computed<SliceSummary[]>(() => {
    const filter = this.filter().toLowerCase();
    return this.slices.hasValue()
      ? this.slices
          .value()
          .filter(
            (slice) =>
              filter.length == 0 ||
              slice['kss:name']?.toLowerCase().includes(filter),
          )
      : [];
  });

  editSlice(event: Event, sliceName: string): void {
    event.stopPropagation();

    this.router.navigate(['/slices', sliceName, 'edit']);
  }

  querySlice(event: Event, sliceName: string): void {
    event.stopPropagation();
    this.router.navigate(['/slices', sliceName, 'query']);
  }

  writeToSlice(event: Event, sliceName: string): void {
    event.stopPropagation();
    this.router.navigate(['/slices/', sliceName, 'changes']);
  }

  deleteSlice(sliceName: string): void {
    const removeFn = () =>
      this.kvasir.deleteSlice(sliceName).subscribe(() => this.slices.reload());
    this.modal.confirm({
      nzContent: `Are you sure you want to remove the slice <strong>${sliceName}</strong>?<br>This action cannot be undone.`,
      nzTitle: `Remove this slice?`,
      nzOkDanger: true,
      nzOkText: 'Remove',
      nzOnOk: removeFn,
      nzWidth: '480px',
    });
  }

  manageTags(event: Event, sliceName: string): void {
    event.stopPropagation();
    this.router.navigate(['/slices/', sliceName, 'tags']);
  }

  copyText(event: Event, str: string) {
    event.stopPropagation();
    this.clipboard.copy(str);
    this.message.info('Copied ID to clipboard');
  }

  getLastMainTags(slice: SliceSummary): string[] {
    const lineage = slice['kss:lineage'];
    if (!lineage) {
      return [];
    }
    return ensureArray(lineage['kss:tags']);
  }
}
