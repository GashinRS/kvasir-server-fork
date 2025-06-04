import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { NzPageHeaderModule } from 'ng-zorro-antd/page-header';
import { map } from 'rxjs';
import { GraphiqlEditorComponent } from '../components/graphiql-editor/graphiql-editor.component';
import { ConfigService } from '../services/config.service';
import { SessionService } from '../services/session.service';
import { Slice } from '../types';

@Component({
  selector: 'app-slice-query',
  imports: [NzPageHeaderModule, GraphiqlEditorComponent],
  templateUrl: './slice-query.component.html',
  styleUrl: './slice-query.component.less',
})
export class SliceQueryComponent {
  private route = inject(ActivatedRoute);
  private config = inject(ConfigService);
  private session = inject(SessionService);

  slice = rxResource<Slice, unknown>({
    loader: () => this.route.data.pipe(map(({ slice }) => slice)),
  });
  queryEndpoint = computed(
    () =>
      new URL(
        `${this.config.host}/${this.session.podName()}/slices/${this.slice.value()?.['kss:name']}/query`,
      ),
  );
  historyKey = computed(
    () => `${this.session.podName()}:${this.slice.value()?.['kss:name']}`,
  );
}
