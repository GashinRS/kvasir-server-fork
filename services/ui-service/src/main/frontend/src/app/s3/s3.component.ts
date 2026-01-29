import { DatePipe, DecimalPipe } from '@angular/common';
import {
  Component,
  computed,
  inject,
  input,
  OnInit,
  resource,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import type { _Object } from '@aws-sdk/client-s3';
import { Base64 } from 'js-base64';
import { NzBreadCrumbModule } from 'ng-zorro-antd/breadcrumb';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzDividerModule } from 'ng-zorro-antd/divider';
import { NzDropdownModule } from 'ng-zorro-antd/dropdown';
import { NzEmptyModule } from 'ng-zorro-antd/empty';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzModalModule, NzModalService } from 'ng-zorro-antd/modal';
import { NzPageHeaderModule } from 'ng-zorro-antd/page-header';
import { NzSpaceModule } from 'ng-zorro-antd/space';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import {
  NzUploadChangeParam,
  NzUploadModule,
  NzUploadXHRArgs,
} from 'ng-zorro-antd/upload';
import { asyncScheduler, scheduled } from 'rxjs';
import { CreateRelationshipComponent } from '../modals/create-relationship/create-relationship.component';
import { RebacService } from '../services/rebac.service';
import { S3wrapperService } from '../services/s3wrapper.service';
import { SessionService } from '../services/session.service';
import { RelationshipDefinition, WriteTransaction } from '../types';
import { AT_CONTEXT_KSS_FGA } from '../util/constants';
import { ensureArray, ensureSlashAtStart } from '../util/utils';
import { NzFlexDirective, NzFlexModule } from 'ng-zorro-antd/flex';

@Component({
  selector: 'app-s3',
  imports: [
    NzBreadCrumbModule,
    NzButtonModule,
    NzDropdownModule,
    NzEmptyModule,
    NzIconModule,
    NzInputModule,
    NzModalModule,
    NzPageHeaderModule,
    NzSpaceModule,
    NzFlexModule,
    NzTableModule,
    NzUploadModule,
    NzDividerModule,
    NzModalModule,
    NzTooltipModule,
    DatePipe,
    DecimalPipe,
    FormsModule,
    RouterModule,
  ],
  templateUrl: './s3.component.html',
  styleUrl: './s3.component.less',
})
export class S3Component implements OnInit {
  private s3w = inject(S3wrapperService);
  private session = inject(SessionService);
  private router = inject(Router);
  private msg = inject(NzMessageService);
  private modal = inject(NzModalService);
  private rebac = inject(RebacService);

  /** Custom upload request */
  customRequestFn = (items: NzUploadXHRArgs) => {
    const key = this.prefix()
      ? [this.prefix(), items.file.name].join('/')
      : items.file.name;

    const file = items.postFile as File;
    return scheduled(
      this.s3w.uploadObject(key, file),
      asyncScheduler,
    ).subscribe({
      error: (err) => console.log(err),
      next: () => {},
      complete: () => {
        this.dataList.reload();
      },
    });
  };

  podName = this.session.podName()!;
  prefixRaw = input<string>('');
  prefix = computed(() =>
    this.prefixRaw()?.length > 0 ? Base64.decode(this.prefixRaw()) : undefined,
  );

  prefixModalVisible = false;
  prefixInputValue: string = '';

  /** Main s3 object list */
  dataList = resource({
    params: () => ({ prefix: this.prefix() }),
    loader: async ({ params }) => {
      const distinctFolders = new Set();
      const result = await this.s3w.listObjects(params.prefix);
      return ensureArray(result.Contents)
        .map((obj: any) => this.mapToFileAndFolder(obj))
        .filter((obj: FileOrFolder) => {
          if (obj.type === 'folder') {
            const name = this.unPrefix(obj.Key!);
            if (distinctFolders.has(name)) {
              return false;
            } else {
              distinctFolders.add(name);
            }
          }
          return true;
        });
    },
  });

  ngOnInit(): void {
    this.dataList.reload();
  }

  showModal(): void {
    this.prefixModalVisible = true;
  }

  handleOk(): void {
    this.prefixModalVisible = false;
    const prefixNew = this.prefix()
      ? this.prefix() + '/' + this.prefixInputValue
      : this.prefixInputValue;
    this.goToPage(prefixNew);
  }

  handleCancel(): void {
    this.prefixModalVisible = false;
  }

  private goToPage(prefix?: string): void {
    const prefixEnc = prefix ? Base64.encodeURL(prefix) : undefined;
    this.router.navigate(['s3', prefixEnc]);
  }

  goToFolder(folderName: string): void {
    const prefix = this.prefix()
      ? this.prefix()! + '/' + folderName
      : folderName;
    const prefixEnc = prefix ? Base64.encodeURL(prefix) : undefined;
    this.router.navigate(['s3', prefixEnc]);
  }

  goToBreadcrumbIndex(idx: number) {
    const prefixNew = this.prefix()
      ?.split('/')
      .slice(0, idx + 1)
      .join('/');
    const prefixEnc = prefixNew ? Base64.encodeURL(prefixNew) : undefined;
    this.router.navigate(['s3', prefixEnc]);
  }

  handleChange(info: NzUploadChangeParam): void {
    if (info.file.status !== 'uploading') {
      // console.log(info.file, info.fileList);
    }
    if (info.file.status === 'done') {
      this.msg.success(`${info.file.name} file uploaded successfully`);
    } else if (info.file.status === 'error') {
      this.msg.error(`${info.file.name} file upload failed.`);
    }
  }

  async downloadObject(key: string): Promise<void> {
    const res = await this.s3w.downloadObject(key);
    const bytes = await res.arrayBuffer();
    const blob = new Blob([bytes], { type: this.s3w.keyToMIME(key) });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = key.slice(key.lastIndexOf('/') + 1);
    document.body.appendChild(a);
    a.click();

    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }

  async deleteObject(key: string): Promise<void> {
    const removeFn = async () => {
      await this.s3w.deleteObject(key);
      this.dataList.reload();
    };
    this.modal.confirm({
      nzContent: `<pre>` + key + `</pre>`,
      nzTitle: 'Remove this resource?',
      nzOkDanger: true,
      nzOkText: 'Remove',
      nzOnOk: removeFn,
      nzWidth: '680px',
    });
  }

  createPolicyRule(key: string): void {
    const ref = this.modal.create<
      CreateRelationshipComponent,
      any,
      RelationshipDefinition
    >({
      nzTitle: 'Create a new relationship',
      nzContent: CreateRelationshipComponent,
      nzClassName: 'create-relationship-content',
      nzData: {
        subject: null,
        relation: null,
        object: `/${this.podName}/s3${ensureSlashAtStart(key)}`,
      },
    });
    ref.afterClose.asObservable().subscribe((relDef) => {
      if (relDef) {
        const trans: WriteTransaction = {
          '@context': AT_CONTEXT_KSS_FGA,
          'kss:insert': [relDef],
        };
        this.rebac
          .postRelationship(trans)
          .subscribe(() => this.router.navigate(['access-control']));
      }
    });
  }

  unPrefix(key: string): string {
    const len = !!this.prefix() ? this.prefix()!.length + 1 : 0;
    const tmp = key.slice(len);
    const idx = tmp.indexOf('/');
    const end = idx === -1 ? tmp.length : idx;
    return tmp.slice(0, end);
  }

  private mapToFileAndFolder(obj: any): FileOrFolder {
    let name = obj.Key as string;
    if (this.prefix()) {
      name = name.slice(this.prefix()!.length + 1);
    }
    const idx = name.indexOf('/') ?? -1;
    if (idx === -1) {
      return { ...obj, type: 'file' } as FileOrFolder;
    } else {
      return { ...obj, Name: name.substring(0, idx), type: 'folder' };
    }
  }
}

interface FileOrFolder extends _Object {
  type: 'file' | 'folder';
}
