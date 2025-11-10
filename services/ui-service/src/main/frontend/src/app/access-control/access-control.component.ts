import { Component, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterModule } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzGridModule } from 'ng-zorro-antd/grid';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzModalModule, NzModalService } from 'ng-zorro-antd/modal';
import { NzPageHeaderModule } from 'ng-zorro-antd/page-header';
import { NzSpaceModule } from 'ng-zorro-antd/space';
import { NzTableModule } from 'ng-zorro-antd/table';
import { CheckPermissionComponent } from '../modals/check-permission/check-permission.component';
import { CreateRelationshipComponent } from '../modals/create-relationship/create-relationship.component';
import { RebacService } from '../services/rebac.service';
import {
  Relationship,
  RelationshipDefinition,
  WriteTransaction,
} from '../types';
import { AT_CONTEXT_KSS_FGA } from '../util/constants';
import { AccessControlSubjectComponent } from '../components/access-control-subject/access-control-subject.component';
import { AccessControlResourceComponent } from '../components/access-control-resource/access-control-resource.component';
import { AccessControlRelationComponent } from '../components/access-control-relation/access-control-relation.component';

@Component({
  selector: 'app-access-control',
  imports: [
    NzButtonModule,
    NzPageHeaderModule,
    NzGridModule,
    NzModalModule,
    NzTableModule,
    NzIconModule,
    NzSpaceModule,
    RouterModule,
    NzModalModule,
    AccessControlSubjectComponent,
    AccessControlResourceComponent,
    AccessControlRelationComponent,
  ],
  templateUrl: './access-control.component.html',
  styleUrl: './access-control.component.less',
})
export class AccessControlComponent {
  // DI
  private rebac = inject(RebacService);
  private modal = inject(NzModalService);

  relationships = rxResource({
    stream: () => this.rebac.listRelationships(),
  });

  createRelationship() {
    const ref = this.modal.create<
      CreateRelationshipComponent,
      any,
      RelationshipDefinition
    >({
      nzTitle: 'Create a new relationship',
      nzContent: CreateRelationshipComponent,
      nzClassName: 'create-relationship-content',
    });
    ref.afterClose.asObservable().subscribe((relDef) => {
      if (relDef) {
        const trans: WriteTransaction = {
          '@context': AT_CONTEXT_KSS_FGA,
          'kss:insert': [relDef],
        };
        this.rebac
          .postRelationship(trans)
          .subscribe(() => this.relationships.reload());
      }
    });
  }

  checkPermission() {
    this.modal.create<CheckPermissionComponent>({
      nzTitle: 'Check permission',
      nzContent: CheckPermissionComponent,
      nzClassName: 'check-permission-content',
    });
  }

  remove(relation: Relationship) {
    const trans: WriteTransaction = {
      '@context': AT_CONTEXT_KSS_FGA,
      'kss:delete': [relation.toDefinition()],
    };
    const removeFn = () =>
      this.rebac
        .postRelationship(trans)
        .subscribe(() => this.relationships.reload());
    this.modal.confirm({
      nzContent:
        `<pre>` + JSON.stringify(relation.toDefinition(), null, 2) + `</pre>`,
      nzTitle: 'Remove this rule?',
      nzOkDanger: true,
      nzOkText: 'Remove',
      nzOnOk: removeFn,
      nzWidth: '680px',
    });
  }

  getSubjectIcon(rel: Relationship): string {
    const id = rel['@id'];
    if (id.startsWith('kss-fga:User')) {
      return 'user';
    }
    if (id.startsWith('mailto:')) {
      return 'mail';
    }
    if (id.startsWith('https://')) {
      return 'idcard';
    }
    return 'question-circle';
  }
}
