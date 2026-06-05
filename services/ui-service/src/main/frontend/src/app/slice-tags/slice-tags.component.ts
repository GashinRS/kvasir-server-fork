import { DatePipe } from '@angular/common';
import {
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  signal,
  ViewContainerRef,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzEmptyModule } from 'ng-zorro-antd/empty';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzListModule } from 'ng-zorro-antd/list';
import { NzModalModule, NzModalService } from 'ng-zorro-antd/modal';
import { NzPageHeaderModule } from 'ng-zorro-antd/page-header';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { map } from 'rxjs/internal/operators/map';
import {
  EditTagComponent,
  EditTagModalData,
  EditTagModalResult,
} from '../modals/edit-tag/edit-tag.component';
import { KvasirService } from '../services/kvasir.service';
import { EntityTag } from '../types';
import { HelpComponent } from '../components/help/help.component';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { NzSpaceModule } from 'ng-zorro-antd/space';

@Component({
  selector: 'app-slice-tags',
  imports: [
    NzPageHeaderModule,
    NzListModule,
    NzButtonModule,
    NzTagModule,
    NzInputModule,
    NzRadioModule,
    NzEmptyModule,
    NzTooltipModule,
    NzModalModule,
    FormsModule,
    RouterModule,
    DatePipe,
    NzIconModule,
    HelpComponent,
    NzSpaceModule,
  ],
  templateUrl: './slice-tags.component.html',
  styleUrl: './slice-tags.component.less',
})
export class SliceTagsComponent {
  // DI
  private kvasir = inject(KvasirService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private modal = inject(NzModalService);
  private readonly sliceId = this.route.snapshot.paramMap.get('sliceId')!;
  private sanitized = inject(DomSanitizer);

  filter = signal<string>('');

  sliceName = input.required<string>({ alias: 'sliceId' });

  tags = rxResource({
    stream: () =>
      this.kvasir
        .listSliceTags(this.sliceId)
        .pipe(map((graphLd) => graphLd?.['@graph'] || ([] as EntityTag[]))),
  });

  defaultRevisionId = linkedSignal<EntityTag[] | undefined, string | null>({
    source: this.tags.value,
    computation: (tags) =>
      tags?.find((tag) => tag['kss:tag'] === 'default')?.['kss:revisionId'] ??
      null,
  });

  filteredTags = computed(() => {
    const filter = this.filter().toLowerCase();
    return this.tags.hasValue()
      ? this.tags
          .value()
          .filter(
            (tag) =>
              filter.length == 0 ||
              tag['kss:tag']!.toLowerCase().includes(filter),
          )
      : [];
  });

  EDIT_TAG_TOOLTIP = this.sanitized.bypassSecurityTrustHtml(
    `Apply the <u>same</u> tag to a new revision`,
  );
  FORK_TAG_TOOLTIP = this.sanitized.bypassSecurityTrustScript(
    `Apply a new tag to a new revision`,
  );

  constructor(private viewContainerRef: ViewContainerRef) {}

  colorForTag(tag: EntityTag): string {
    return tag['kss:revisionId'] === this.defaultRevisionId()
      ? 'orange'
      : 'blue';
  }

  doEdit(tag: EntityTag) {
    const sameRevisionTags =
      this.tags
        .value()
        ?.filter(
          (item) =>
            item['kss:revisionId'] === tag['kss:revisionId'] &&
            item['kss:tag'] !== tag['kss:tag'],
        ) ?? [];

    const editAll = () =>
      this.router.navigate(
        ['slices', this.sliceId, 'tags', tag['kss:tag'], 'edit'],
        {
          queryParams: {
            revisionId: tag['kss:revisionId'],
          },
        },
      );
    const editOne = () =>
      this.router.navigate([
        'slices',
        this.sliceId,
        'tags',
        tag['kss:tag'],
        'edit',
      ]);

    if (sameRevisionTags.length > 0) {
      const ref = this.modal.create<
        EditTagComponent,
        EditTagModalData,
        EditTagModalResult
      >({
        nzContent: EditTagComponent,
        nzViewContainerRef: this.viewContainerRef,
        nzData: {
          tag: tag['kss:tag']!,
          aliases: sameRevisionTags.map((tag) => tag['kss:tag']!),
        },
      });
      ref.afterClose.subscribe((result) => {
        if (result === EditTagModalResult.ALL_TAGS) {
          editAll();
        } else if (result === EditTagModalResult.THIS_TAG_ONLY) {
          editOne();
        }
      });
    } else {
      // No aliases, edit this tag only
      editOne();
    }
  }

  doFork(tag: EntityTag) {
    this.router.navigate([
      'slices',
      this.sliceId,
      'tags',
      tag['kss:tag'],
      'fork',
    ]);
  }

  doTagAsDefault(tag: EntityTag) {
    this.kvasir
      .aliasTag(this.sliceId, tag, 'default')
      .subscribe(() => this.tags.reload());
  }

  doDelete(tag: EntityTag) {
    const deleteFn = () =>
      this.kvasir.deleteTag(this.sliceId, tag['kss:tag']).subscribe(() => {
        this.tags.reload();
      });

    this.modal.confirm({
      nzTitle: 'Are you sure you want to delete this tag?',
      nzContent: `Tag <b>${tag['kss:tag']}</b> will be deleted. This action cannot be undone.`,
      nzOkText: 'Delete tag',
      nzOnOk: deleteFn,
      nzOkDanger: true,
    });
  }

  writeToSlice(sliceName: string, tag: EntityTag) {
    this.router.navigate(['/slices', sliceName, 'changes'], {
      queryParams: { tag: tag['kss:tag'] },
    });
  }

  querySlice(sliceName: string, tag: EntityTag) {
    this.router.navigate(['/slices', sliceName, 'query'], {
      queryParams: { tag: tag['kss:tag'] },
    });
  }
}
