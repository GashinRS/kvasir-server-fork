import { Component, computed, input } from '@angular/core';
import { NzSpaceModule } from 'ng-zorro-antd/space';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { RevisionLineage } from '../../types';
import { ensureArray } from '../../util/utils';
import { RevisionTagComponent } from '../revision-tag/revision-tag.component';

@Component({
  selector: 'app-slice-lineage',
  imports: [NzSpaceModule, NzTagModule, RevisionTagComponent],
  template: `
    @for (tag of tags(); track tag) {
      <app-revision-tag [tag]="tag" [revisionsAhead]="revisionsAhead()" />
    }
  `,
  styles: ``,
})
export class SliceLineageComponent {
  lineage = input.required<RevisionLineage>();
  tags = computed<string[]>(() => ensureArray(this.lineage()['kss:tags']));

  readonly PRIMARY_COLOR = 'blue'; //hsl(209, 100%, 75%)';

  revisionsAhead = computed<number>(
    () => this.lineage()['kss:numberOfRevisionsAhead'],
  );
}
