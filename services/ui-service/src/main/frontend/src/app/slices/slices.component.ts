import { Component, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzPageHeaderModule } from 'ng-zorro-antd/page-header';
import { NzSpaceModule } from 'ng-zorro-antd/space';
import { NzTableModule } from 'ng-zorro-antd/table';
import { HelpComponent } from '../components/help/help.component';
import { KvasirService } from '../services/kvasir.service';
import { NzFlexDirective } from 'ng-zorro-antd/flex';

@Component({
  selector: 'app-slices',
  standalone: true,
  imports: [
    NzPageHeaderModule,
    NzTableModule,
    NzButtonModule,
    NzSpaceModule,
    NzFlexDirective,
    RouterLink,
    HelpComponent,
  ],
  templateUrl: './slices.component.html',
  styleUrl: './slices.component.less',
})
export class SlicesComponent {
  // DI
  private kvasir = inject(KvasirService);
  private router = inject(Router);

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
}
