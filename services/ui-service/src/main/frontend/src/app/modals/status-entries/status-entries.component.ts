import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { NZ_MODAL_DATA } from 'ng-zorro-antd/modal';
import { NzTimelineModule } from 'ng-zorro-antd/timeline';
import { NzTypographyModule } from "ng-zorro-antd/typography";
import { ChangeResultCode, ChangeStatusEntry } from '../../types';
import { sortStatusEntries } from '../../util/utils';

@Component({
  selector: 'app-status-entries',
  imports: [DatePipe, NzTimelineModule, NzTypographyModule],
  templateUrl: './status-entries.component.html',
  styleUrl: './status-entries.component.less'
})
export class StatusEntriesComponent {
  data: ChangeStatusEntry[] = inject(NZ_MODAL_DATA);
  dataSorted = signal(this.data.sort(sortStatusEntries()));

  getColor(event: ChangeStatusEntry): 'gray' | 'blue' | 'green' | 'red' {
    const code: ChangeResultCode = ChangeResultCode[event['kss:statusCode'] as unknown as keyof typeof ChangeResultCode];
    switch (code) {
      default:
      case ChangeResultCode.QUEUED:
        return "gray";
      case ChangeResultCode.PROCESSING:
        return "blue";
      case ChangeResultCode.COMMITTED:
        return "green";
      case ChangeResultCode.ASSERTION_FAILED:
      case ChangeResultCode.INTERNAL_ERROR:
      case ChangeResultCode.NO_MATCHES:
      case ChangeResultCode.TOO_MANY_MATCHES:
      case ChangeResultCode.VALIDATION_ERROR:
        return "red";
    }
  }

  getTime(event: ChangeStatusEntry): string {
    return event['kss:timestamp']
  }

}
