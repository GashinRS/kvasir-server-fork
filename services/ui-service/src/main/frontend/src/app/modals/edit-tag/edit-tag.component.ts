import { Component, computed, inject } from '@angular/core';
import { NzButtonModule } from 'ng-zorro-antd/button';
import {
  NZ_MODAL_DATA,
  NzModalFooterDirective,
  NzModalRef,
  NzModalTitleDirective,
} from 'ng-zorro-antd/modal';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { NzTypographyModule } from 'ng-zorro-antd/typography';

export interface EditTagModalData {
  tag: string;
  aliases: string[];
}

export enum EditTagModalResult {
  ALL_TAGS,
  THIS_TAG_ONLY,
}

@Component({
  selector: 'app-edit-tag',
  imports: [
    NzTagModule,
    NzButtonModule,
    NzModalTitleDirective,
    NzModalFooterDirective,
    NzTypographyModule,
  ],
  templateUrl: './edit-tag.component.html',
  styleUrl: './edit-tag.component.less',
})
export class EditTagComponent {
  data = inject<EditTagModalData>(NZ_MODAL_DATA);
  modal = inject(NzModalRef);

  tag = computed(() => this.data.tag);
  aliases = computed(() => this.data.aliases);

  constructor() {}

  destroyModal(): void {
    this.modal.destroy();
  }

  confirmAllTags(): void {
    this.modal.destroy(EditTagModalResult.ALL_TAGS);
  }

  confirmThisTagOnly(): void {
    this.modal.destroy(EditTagModalResult.THIS_TAG_ONLY);
  }
}
