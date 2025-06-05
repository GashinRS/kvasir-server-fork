import { Component, input } from '@angular/core';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzToolTipModule } from 'ng-zorro-antd/tooltip';

@Component({
  selector: 'app-help',
  imports: [NzIconModule, NzToolTipModule],
  template: `
    <div class="top" [nz-tooltip]="text()">
      <span
        nz-icon
        nzType="question-circle"
        [nzTheme]="type() == 'primary' ? 'fill' : 'outline'"
      ></span>
    </div>
  `,
  styles: [
    `
      .top {
        display: inline-block;
        cursor: help;
      }
    `,
  ],
})
export class HelpComponent {
  text = input.required<string>();
  type = input<'primary' | 'secondary'>('primary');
}
