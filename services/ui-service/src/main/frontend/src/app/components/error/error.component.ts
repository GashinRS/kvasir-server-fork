import { Component, inject } from '@angular/core';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NZ_MODAL_DATA, NzModalModule, NzModalRef } from 'ng-zorro-antd/modal';
import { NzTypographyModule } from 'ng-zorro-antd/typography';

import { Clipboard } from '@angular/cdk/clipboard';
import { NzNotificationService } from 'ng-zorro-antd/notification';

export interface KvasirError {
  statusCode?: number;
  description?: string;
  message: string;
  stack?: string;
}

type CopyTxt = 'Copy error' | 'Copied...';

@Component({
  selector: 'app-error',
  imports: [NzModalModule, NzTypographyModule, NzIconModule, NzButtonModule],
  templateUrl: './error.component.html',
  styleUrl: './error.component.less',
})
export class ErrorComponent {
  readonly modal = inject(NzModalRef);
  readonly error: KvasirError = inject(NZ_MODAL_DATA);
  copyBtnTxt: CopyTxt = 'Copy error';

  // DI
  private clipboard = inject(Clipboard);
  private notify = inject(NzNotificationService);

  copyError(): void {
    this.clipboard.copy(JSON.stringify(this.error, null, 4));
    this.copyBtnTxt = 'Copied...';
    setTimeout(() => (this.copyBtnTxt = 'Copy error'), 1500);
  }

  get isCopyBtnDisabled() {
    return this.copyBtnTxt == 'Copied...';
  }
}
