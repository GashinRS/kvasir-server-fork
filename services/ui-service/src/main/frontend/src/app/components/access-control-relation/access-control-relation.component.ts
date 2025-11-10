import { Component, computed, input } from '@angular/core';
import { Relationship } from '../../types';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';

@Component({
  selector: 'app-access-control-relation',
  imports: [NzIconModule, NzTooltipModule],
  template: ` <span
    [nz-tooltip]="isKssFgaRelation() ? relationship().getRelationName() : null"
  >
    @if (isKssFgaRelation()) {
      <span nz-icon [nzType]="icon()"></span>
    }
    <span>{{ label() }}</span>
  </span>`,
  styles: `
    span + span {
      margin-left: 6px;
    }
  `,
})
export class AccessControlRelationComponent {
  relationship = input.required<Relationship>();

  icon = computed(() => {
    const rel = this.relationship().getRelationName();
    switch (rel) {
      case 'kss-fga:reader':
        return 'read';
      case 'kss-fga:writer':
        return 'signature';
      case 'kss-fga:deleter':
        return 'delete';
      case 'kss-fga:blocked':
        return 'stop';
      case 'kss-fga:manager':
        return 'key';
      case 'kss-fga:owner':
        return 'crown';
      default:
        return 'question-circle';
    }
  });

  label = computed(() => {
    const rel = this.relationship().getRelationName();
    if (this.isKssFgaRelation()) {
      return rel.slice('kss-fga:'.length);
    }
    return rel;
  });

  isKssFgaRelation = computed(() => {
    const rel = this.relationship().getRelationName();
    return rel.startsWith('kss-fga:');
  });
}
