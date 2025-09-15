import { Component, input } from '@angular/core';
import * as N3 from 'n3';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzPopoverModule } from 'ng-zorro-antd/popover';

@Component({
  selector: 'app-literal-badge',
  imports: [NzIconModule, NzPopoverModule],
  templateUrl: './literal-badge.component.html',
  styleUrl: './literal-badge.component.less',
})
export class LiteralBadgeComponent {
  literal = input.required<N3.Literal>();
}
