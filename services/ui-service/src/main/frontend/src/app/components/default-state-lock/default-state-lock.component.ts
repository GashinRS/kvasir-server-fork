import {
  Component,
  computed,
  inject,
  input,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import {
  ControlValueAccessor,
  FormsModule,
  NG_VALUE_ACCESSOR,
} from '@angular/forms';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzSkeletonModule } from 'ng-zorro-antd/skeleton';
import { NzSpaceModule } from 'ng-zorro-antd/space';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { DevSettingsService } from '../../services/dev-settings.service';
import { HelpComponent } from '../help/help.component';

@Component({
  selector: 'app-default-state-lock',
  imports: [
    NzSpaceModule,
    NzIconModule,
    NzSwitchModule,
    FormsModule,
    NzTooltipModule,
    NzInputModule,
    NzSkeletonModule,
    HelpComponent,
  ],
  templateUrl: './default-state-lock.component.html',
  styleUrl: './default-state-lock.component.less',
  encapsulation: ViewEncapsulation.None,
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: DefaultStateLockComponent,
      multi: true,
    },
  ],
})
export class DefaultStateLockComponent implements ControlValueAccessor {
  defaultState = input.required<any>();
  label = input.required<string>();
  type = input.required<'switch' | 'code-json' | 'fieldset'>();
  displayType = computed(() => {
    switch (this.type()) {
      case 'code-json':
      case 'fieldset':
        return 'block';
      default:
      case 'switch':
        return 'inline';
    }
  });
  onChange = (value: boolean) => {};
  onTouched = () => {};
  private pristine = true;

  defaultStateStringified = computed(() => {
    return JSON.stringify(this.defaultState(), null, 4);
  });

  // DI
  settings = inject(DevSettingsService);

  locked = signal(true);

  unlock() {
    this.locked.set(false);
    this.onChange(false);
    this.touched();
  }

  lock() {
    this.locked.set(true);
    this.onChange(true);
    this.touched();
  }

  writeValue(locked: boolean): void {
    this.locked.set(locked);
  }

  registerOnChange(fn: any): void {
    this.onChange = fn;
  }
  registerOnTouched(fn: any): void {
    this.onTouched = fn;
  }

  private touched() {
    if (this.pristine) {
      this.pristine = false;
      this.onTouched();
    }
  }
}
