import { Component, computed, input } from '@angular/core';
import { NzTagComponent } from 'ng-zorro-antd/tag';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';

@Component({
  selector: 'app-revision-tag',
  imports: [NzTagComponent, NzTooltipModule],
  template: `
    <ng-template #tooltip>
      <span [innerHTML]="tooltipTxt()"></span>
    </ng-template>
    @if (revisionsAhead() == 0) {
      <nz-tag [nzColor]="PRIMARY_COLOR" [nz-tooltip]="tooltip">{{
        tag()
      }}</nz-tag>
    } @else {
      <div class="revTag" [nz-tooltip]="tooltip">
        <div class="tag">{{ tag() }}</div>
        <div class="ahead">+{{ revisionsAhead() }}</div>
      </div>
    }
  `,
  styles: `
    .revTag {
      display: inline-block;
      font-size: 14px;
      font-variant: tabular-nums;
      list-style: none;
      font-feature-settings: 'tnum';
      font-size: 12px;
      line-height: 20px;
      margin: 0;
      margin-right: 8px;
      padding: 0;
      border: 1px solid #91d5ff;
      border-radius: 2px;

      @myColor: #096dd9;

      .tag {
        display: inline-block;
        box-sizing: content-box;
        height: auto;
        padding: 0 7px;
        white-space: nowrap;
        background: #fafafa;
        border-right: 1px solid #91d5ff;
        opacity: 1;
        transition: all 0.3s;
        color: #096dd9;
        background: #e6f7ff;
        border-color: #91d5ff;
      }
      .ahead {
        display: inline-block;
        padding: 0 3px;
        color: lighten(@myColor, 40%);
      }
    }
  `,
})
export class RevisionTagComponent {
  tag = input.required<string>();
  revisionsAhead = input.required<number>();

  tooltipTxt = computed(() => {
    const tag = this.tag();
    const ahead = this.revisionsAhead();
    return ahead > 0
      ? `Currently <u>${ahead} revision${ahead > 1 ? 's' : ''}</u> ahead of <u>${tag}</u>`
      : `Currently @ <u>${tag}</u>`;
  });

  readonly PRIMARY_COLOR = 'blue';
}
