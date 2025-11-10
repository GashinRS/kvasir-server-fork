import { Component, computed, inject, input, signal } from '@angular/core';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { Relationship } from '../../types';
import { compact } from 'jsonld';
import { ConfigService } from '../../services/config.service';
import { SessionService } from '../../services/session.service';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';

@Component({
  selector: 'app-access-control-resource',
  imports: [NzIconModule, NzTooltipModule],
  template: `
    @if (isPodOnHost()) {
      <span class="pod" [nz-tooltip]="pod()">
        <div class="title">pod</div>
        <div class="label">{{ podName() }}</div>
      </span>
      <span class="resource">{{ resource() }}</span>
    } @else {
      {{ resource() }}
    }
  `,
  styles: `
    @primary: #1890FF; // primary
    @margin: 5px;
    span + span {
      margin-left: 4px;
    }
    .pod {
      display: inline-grid;
      grid-template-columns: auto auto;
      justify-content: start;
      border: 1px solid @primary;
      border-radius: 4px;

      .title {
        padding: 0 @margin;
        font-family: monospace;
        background-color: @primary;
        color: white;
      }

      .label {
        padding: 0 @margin;
        color: @primary;
      }
    }
    .resource {
      font-family: monospace;
      white-space: pre;
    }
  `,
})
export class AccessControlResourceComponent {
  relationship = input.required<Relationship>();
  private config = inject(ConfigService);
  private session = inject(SessionService);

  isPodOnHost = computed(() => {
    let obj = this.relationship().getRelationObject()['@id'];
    return obj.startsWith(`${this.config.host}/${this.session.podName()}`);
  });

  pod = signal(`${this.config.host}/${this.session.podName()}`);

  resource = computed(() => {
    let obj = this.relationship().getRelationObject()['@id'];
    if (this.isPodOnHost()) {
      return obj.slice(`${this.config.host}/${this.session.podName()}`.length);
    }
    return obj;
  });

  get podName() {
    return this.session.podName;
  }
}
