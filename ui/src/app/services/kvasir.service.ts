import {
  HttpClient,
  HttpErrorResponse,
  HttpHeaders,
} from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { catchError, map, Observable, throwError } from 'rxjs';
import { KvasirError } from '../components/error/error.component';
import {
  ChangeRecords,
  ChangeReport,
  ChangeRequest,
  GraphLD,
  Paged,
  Pod,
  PodConfiguration,
  Slice,
  SliceInput,
} from '../types';
import { parseLinkHeader } from '../util/utils';
import { ConfigService } from './config.service';
import { ErrorHandlerService } from './error-handler.service';
import { SessionService } from './session.service';

@Injectable({
  providedIn: 'root',
})
export class KvasirService {
  private config = inject(ConfigService);
  private http = inject(HttpClient);
  private session = inject(SessionService);
  private errorHandler = inject(ErrorHandlerService);

  private host = this.config.host;

  constructor() {}

  createChangeRequest(input: ChangeRequest): Observable<string | null> {
    return this.http
      .post<void>(`${this.host}/${this.session.podName()}/changes`, input, {
        headers: new HttpHeaders().append(
          'Content-Type',
          'application/ld+json',
        ),
        observe: 'response',
      })
      .pipe(this.convertErrorToKvasirError())
      .pipe(map((response) => response.headers.get('Location')));
  }

  listChangeReports(): Observable<ChangeReport[]> {
    return this.http
      .get<GraphLD<ChangeReport>>(
        `${this.host}/${this.session.podName()}/changes`,
      )
      .pipe(this.convertErrorToKvasirError())
      .pipe(map((changeLd) => changeLd['@graph']));
  }

  getChangeReport(changeReportId: string): Observable<ChangeReport> {
    return this.http
      .get<ChangeReport>(decodeURIComponent(changeReportId))
      .pipe(this.convertErrorToKvasirError());
  }

  /**
   * List all change records of this ChangeReport
   * @param changeReportId encodeURIComponent(full @id URI of ChangeReport)
   * @param cursor
   * @returns
   */
  listChangeRecords(
    changeReportId: string,
    cursor?: string,
  ): Observable<Paged<ChangeRecords>> {
    let url = `${decodeURIComponent(changeReportId)}/records?pageSize=1000`;
    if (cursor) {
      url += `&cursor=${encodeURIComponent(cursor)}`;
    }
    return this.http
      .get<ChangeRecords>(url, { observe: 'response' })
      .pipe(this.convertErrorToKvasirError())
      .pipe(
        map((response) => {
          const link = parseLinkHeader(response.headers.get('Link'));
          const cursor = link?.['next']
            ? new URL(link?.['next'])?.searchParams.get('cursor')
            : null;
          const content = response.body!;
          return { cursor, content } as Paged<ChangeRecords>;
        }),
      );
  }

  createSliceChangeRequest(
    sliceName: string,
    input: ChangeRequest,
  ): Observable<string | null> {
    return this.http
      .post<void>(
        `${this.host}/${this.session.podName()}/slices/${sliceName}/changes`,
        input,
        {
          headers: new HttpHeaders().append(
            'Content-Type',
            'application/ld+json',
          ),
          observe: 'response',
        },
      )
      .pipe(this.convertErrorToKvasirError())
      .pipe(map((response) => response.headers.get('Location')));
  }

  listSliceChangeReports(sliceName: string): Observable<ChangeReport[]> {
    return this.http
      .get<GraphLD<ChangeReport>>(
        `${this.host}/${this.session.podName()}/slices/${sliceName}/changes`,
      )
      .pipe(this.convertErrorToKvasirError())
      .pipe(map((changeLd) => changeLd['@graph']));
  }

  createSlice(input: SliceInput): Observable<void> {
    return this.http
      .post<void>(`${this.host}/${this.session.podName()}/slices`, input, {
        headers: new HttpHeaders().append(
          'Content-Type',
          'application/ld+json',
        ),
      })
      .pipe(this.convertErrorToKvasirError());
  }

  getSlice(sliceId: string): Observable<Slice> {
    return this.http
      .get<Slice>(`${this.host}/${this.session.podName()}/slices/${sliceId}`)
      .pipe(this.convertErrorToKvasirError());
  }

  deleteSlice(sliceId: string): Observable<void> {
    return this.http
      .delete<void>(`${this.host}/${this.session.podName()}/slices/${sliceId}`)
      .pipe(this.convertErrorToKvasirError());
  }

  updateSlice(sliceId: string, slice: SliceInput): Observable<void> {
    return this.http
      .put<void>(
        `${this.host}/${this.session.podName()}/slices/${sliceId}`,
        slice,
        {
          headers: new HttpHeaders().append(
            'Content-Type',
            'application/ld+json',
          ),
        },
      )
      .pipe(this.convertErrorToKvasirError());
  }

  listSlices(): Observable<Slice[]> {
    return this.http
      .get<GraphLD<Slice>>(`${this.host}/${this.session.podName()}/slices`)
      .pipe(this.convertErrorToKvasirError())
      .pipe(map((sliceLd) => sliceLd['@graph']));
  }

  listPods(): Observable<Pod[]> {
    return this.http
      .get<GraphLD<Pod>>(this.host)
      .pipe(this.convertErrorToKvasirError())
      .pipe(map((podsLd) => podsLd['@graph']));
  }

  getPod(): Observable<Pod> {
    return this.http
      .get<Pod>(`${this.host}/${this.session.podName()}`)
      .pipe(this.convertErrorToKvasirError());
  }

  updatePod(podConfig: PodConfiguration): Observable<void> {
    const cfg = {
      '@context': {
        kss: 'https://kvasir.discover.ilabt.imec.be/vocab#',
      },
      'kss:configuration': podConfig,
    };
    return this.http
      .put<void>(`${this.host}/${this.session.podName()}`, cfg, {
        headers: new HttpHeaders().append(
          'Content-Type',
          'application/ld+json',
        ),
      })
      .pipe(this.convertErrorToKvasirError());
  }

  createPod(podName: string): Observable<void> {
    const input = {
      '@context': {
        kss: 'https://kvasir.discover.ilabt.imec.be/vocab#',
      },
      'kss:name': podName,
      'kss:configuration': {},
    };
    return this.http
      .post<any>(`${this.host}`, input, {
        headers: new HttpHeaders().append(
          'Content-Type',
          'application/ld+json',
        ),
      })
      .pipe(this.convertErrorToKvasirError());
  }

  private convertErrorToKvasirError = <T>() => {
    return catchError<T, Observable<never>>((res: HttpErrorResponse) => {
      const err: KvasirError = {
        statusCode: res.status,
        message:
          res.error.details ??
          (res.error && JSON.stringify(res.error, null, 4)) ??
          res.statusText,
        stack: res.error.stack,
      };
      this.errorHandler.showInModal(err);
      return throwError(() => err);
    });
  };
}
