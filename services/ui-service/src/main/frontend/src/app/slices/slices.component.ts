import { Clipboard } from '@angular/cdk/clipboard';
import { Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzPageHeaderModule } from 'ng-zorro-antd/page-header';
import { NzPopoverModule } from 'ng-zorro-antd/popover';
import { NzSpaceModule } from 'ng-zorro-antd/space';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { NzTypographyModule } from 'ng-zorro-antd/typography';
import { KvasirService } from '../services/kvasir.service';

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
    RouterLink,
  ],
  templateUrl: './slices.component.html',
  styleUrl: './slices.component.less',
})
export class SlicesComponent {
  // DI
  private kvasir = inject(KvasirService);
  private router = inject(Router);
  private clipboard = inject(Clipboard);

  clickToCopyCss = signal<'click-to-copy' | 'clicked'>('click-to-copy');

  slices = rxResource({
    stream: () => this.kvasir.listSlices(),
  });

  editSlice(sliceName: string): void {
    this.router.navigate(['/slices', sliceName, 'edit']);
  }

  querySlice(sliceName: string): void {
    this.router.navigate(['/slices', sliceName, 'query']);
  }

  writeToSlice(sliceName: string): void {
    this.router.navigate(['/slices/', sliceName, 'changes']);
  }

  deleteSlice(sliceName: string): void {
    this.kvasir.deleteSlice(sliceName).subscribe(() => this.slices.reload());
  }

  copyText(str: string) {
    this.clickToCopyCss.set('clicked');
    setTimeout(() => {
      this.clickToCopyCss.set('click-to-copy');
    }, 100);
    this.clipboard.copy(str);
  }
}
