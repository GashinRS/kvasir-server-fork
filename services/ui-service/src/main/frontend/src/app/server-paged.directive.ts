import { ListRange } from '@angular/cdk/collections';
import {
  AfterViewInit,
  Directive,
  EventEmitter,
  OnDestroy,
} from '@angular/core';
import { NzTableComponent } from 'ng-zorro-antd/table';
import { distinctUntilKeyChanged, Subject, Subscription } from 'rxjs';

const DEFAULT_FETCH_LIMIT = 100;

@Directive({
  selector: 'nz-table[server-paged]',
  outputs: ['fetchNextPage'],
  inputs: ['fetchLimit'],
  standalone: true,
})
export class ServerPagedDirective<T> implements AfterViewInit, OnDestroy {
  /**
   * Event to request fetching a new page, the data should be added to the NzTableComponent data field, by recreating the array in total.
   */
  fetchNextPage = new EventEmitter<RangeDiff>();
  /**
   *  Item count difference from end of virtual list, that will trigger a fetchNextPage event
   */
  fetchLimit: number = DEFAULT_FETCH_LIMIT;

  private debouncer$ = new Subject<RangeDiff>();
  private sub?: Subscription;

  constructor(private el: NzTableComponent<T>) {
    this.sub = this.debouncer$
      .pipe(distinctUntilKeyChanged('totalRecords'))
      .subscribe((rangeDiff) => this.fetchNextPage.emit(rangeDiff));
  }

  ngAfterViewInit(): void {
    this.el.cdkVirtualScrollViewport?.renderedRangeStream.subscribe({
      next: (range) => this.onRenderedRangeChange(range),
    });
  }

  ngOnDestroy(): void {
    if (this.sub && !this.sub.closed) {
      this.sub.unsubscribe();
    }
  }

  private onRenderedRangeChange(range: ListRange) {
    const totalRecords = this.el.data.length;
    const limit = this.fetchLimit;
    if (totalRecords > limit) {
      const diff = totalRecords - range.end;
      if (diff <= limit) {
        this.debouncer$.next({ diff, limit, totalRecords });
      }
    }
  }
}

export interface RangeDiff {
  diff: number;
  limit: number;
  totalRecords: number;
}
