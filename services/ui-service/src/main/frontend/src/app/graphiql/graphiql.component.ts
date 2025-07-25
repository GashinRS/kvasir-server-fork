import { Component, inject } from '@angular/core';
import { ConfigService } from '../services/config.service';
import { SessionService } from '../services/session.service';

import { GraphiqlEditorComponent } from '../components/graphiql-editor/graphiql-editor.component';

@Component({
  selector: 'app-graphiql',
  imports: [GraphiqlEditorComponent],
  templateUrl: './graphiql.component.html',
  styleUrl: './graphiql.component.less',
})
export class GraphiqlComponent {
  // DI
  private config = inject(ConfigService);
  private session = inject(SessionService);

  queryEndpoint = new URL(
    `${this.config.host}/${this.session.podName()!}/query`,
  );
  historyKey = this.session.podName() ?? undefined;
}
