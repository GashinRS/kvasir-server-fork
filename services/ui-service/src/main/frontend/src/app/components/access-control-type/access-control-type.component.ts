import { Component, computed, input } from '@angular/core';
import { Relationship } from '../../types';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { NzIconModule } from 'ng-zorro-antd/icon';
import {
  KSS_FGA_EXTERNAL_ACCESS,
  KSS_FGA_EXTERNAL_ACCESS_HTTP_ENDPOINT,
  KSS_FGA_EXTERNAL_ACCESS_UMA,
} from '../../util/constants';

@Component({
  selector: 'app-access-control-type',
  imports: [NzTooltipModule, NzIconModule],
  template: `
    @if (relationship(); as rel) {
      <span [nz-tooltip]="tooltip()" class="help">
        <span nz-icon [nzType]="icon()"></span>
        <span>{{ label() }} </span>
      </span>
    }
  `,
  styles: `
    .help {
      cursor: default;
    }
    span + span {
      margin-left: 6px;
    }
    span[nz-icon] {
      color: #666;
      &:hover {
        color: black;
      }
    }
  `,
})
export class AccessControlTypeComponent {
  relationship = input.required<Relationship>();

  private condition = computed(() => {
    const extAccess =
      this.relationship().getRelationObject()['kss-fga:external_access'];
    return extAccess == null ? null : (extAccess['@id'] ?? null);
  });

  icon = computed(() => {
    switch (this.condition()) {
      case null:
        return 'thunderbolt';
      case KSS_FGA_EXTERNAL_ACCESS_UMA:
        return 'user';
      case KSS_FGA_EXTERNAL_ACCESS_HTTP_ENDPOINT:
        return 'global';
      default:
        return 'question-circle';
    }
  });

  label = computed(() => {
    switch (this.condition()) {
      case null:
        return 'Kvasir';
      case KSS_FGA_EXTERNAL_ACCESS_UMA:
        return 'UMA';
      case KSS_FGA_EXTERNAL_ACCESS_HTTP_ENDPOINT:
        return 'Http Endpoint';
      default:
        return 'question-circle';
    }
  });
  tooltip = computed(() => {
    switch (this.condition()) {
      case null:
        return 'internal ';
      case KSS_FGA_EXTERNAL_ACCESS_UMA:
      case KSS_FGA_EXTERNAL_ACCESS_HTTP_ENDPOINT:
        return 'external';
      default:
        return undefined;
    }
  });
}
