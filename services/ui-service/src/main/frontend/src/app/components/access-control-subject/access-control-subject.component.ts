import { Component, computed, input } from '@angular/core';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { Relationship } from '../../types';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { KSS_FGA_USER_ANONYMOUS, KSS_FGA_USER_WILDCARD } from '../../util/constants';

const PREFIX_KVASIR_USER = 'urn:kvasir-user:';
const PREFIX_MAILTO = 'mailto:';
const PREFIX_WEBID = 'https://';

@Component({
  selector: 'app-access-control-subject',
  imports: [NzIconModule, NzTooltipModule],
  template: `
    @if (relationship(); as rel) {
      <span
        nz-icon
        [nzType]="icon()"
        [nz-tooltip]="tooltip()"
        class="help"
      ></span>
      <span>{{ label() }} </span>
    }
  `,
  styles: `
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
export class AccessControlSubjectComponent {
  relationship = input.required<Relationship>();

  icon = computed(() => {
    const id = this.relationship()['@id'];
    // Special cases first:
    if (id === KSS_FGA_USER_ANONYMOUS) {
      return 'unlock';
    }
    if (id == KSS_FGA_USER_WILDCARD) {
      return 'team'
    }
    // Other types
    if (id.startsWith(PREFIX_KVASIR_USER)) {
      return 'user';
    }
    if (id.startsWith(PREFIX_MAILTO)) {
      return 'mail';
    }
    if (id.startsWith(PREFIX_WEBID)) {
      return 'idcard';
    }
    return 'question-circle';
  });

  label = computed(() => {
    const id = this.relationship()['@id'];
    // Special cases first:
    if (id === KSS_FGA_USER_ANONYMOUS) {
      return 'Unauthed';
    }
    if (id == KSS_FGA_USER_WILDCARD) {
      return 'Everyone'
    }
    // Other types
    if (id.startsWith(PREFIX_KVASIR_USER)) {
      return id.slice(PREFIX_KVASIR_USER.length);
    }
    if (id.startsWith(PREFIX_MAILTO)) {
      return id.slice(PREFIX_MAILTO.length);
    }
    if (id.startsWith(PREFIX_WEBID)) {
      return id.slice(PREFIX_WEBID.length);
    }
    return id;
  });

  tooltip = computed(() => {
    const id = this.relationship()['@id'];
    return id;
    // // Special cases first:
    // if (id === KSS_FGA_USER_ANONYMOUS) {
    //   return KSS_FGA_USER_ANONYMOUS;
    // }
    // if (id == KSS_FGA_USER_WILDCARD) {
    //   return KSS_FGA_USER_WILDCARD
    // }
    // // Other types
    // if (id.startsWith(PREFIX_KVASIR_USER)) {
    //   return PREFIX_KVASIR_USER;
    // }
    // if (id.startsWith(PREFIX_MAILTO)) {
    //   return PREFIX_MAILTO;
    // }
    // if (id.startsWith(PREFIX_WEBID)) {
    //   return PREFIX_WEBID;
    // }
    // return id;
  });
}
