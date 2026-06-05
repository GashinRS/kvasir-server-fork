import {
  HttpClient,
  HttpErrorResponse,
  HttpHeaders,
} from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { catchError, map, Observable, of, throwError } from 'rxjs';
import {
  ChangeRecords,
  ChangeRequest,
  EntityTag,
  GraphLD,
  LD,
  Paged,
  PendingChangeRequest,
  Pod,
  PodConfiguration,
  PodDetails,
  PodSerialized,
  ProcessedChange,
  RegisterPodInput,
  Slice,
  SliceInput,
  SliceSummary,
} from '../types';
import { deserialize, parseLinkHeader } from '../util/utils';
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

  createChangeRequestForTaggedSlice(
    input: ChangeRequest,
    sliceId: string,
    tag: string,
  ): Observable<string | null> {
    return this.http
      .post<void>(
        `${this.host}/${this.session.podName()}/slices/${sliceId}/tags/${tag}/changes`,
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

  listChangeReports(): Observable<ProcessedChange[]> {
    return this.http
      .get<GraphLD<ProcessedChange>>(
        `${this.host}/${this.session.podName()}/changes`,
      )
      .pipe(this.convertErrorToKvasirError())
      .pipe(map((changeLd) => changeLd['@graph']));
  }

  /**
   * Get a processed change by its ChangeReport @id
   * If the change is still pending, a PendingChangeRequest is returned
   * @param changeReportId
   * @returns
   */
  getProcessedChange(
    changeReportId: string,
  ): Observable<PendingChangeRequest | ProcessedChange> {
    return this.http
      .get<PendingChangeRequest | ProcessedChange>(
        decodeURIComponent(changeReportId),
        {
          observe: 'response',
        },
      )
      .pipe(
        map((res) =>
          res.redirected
            ? (res.body as ProcessedChange)
            : (res.body as PendingChangeRequest),
        ),
        this.convertErrorToKvasirError(),
      );
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
    console.log(`Fetching change records from ${url}...`);
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
    tag?: string,
  ): Observable<string | null> {
    const url = tag
      ? `${this.host}/${this.session.podName()}/slices/${sliceName}/tags/${tag}/changes`
      : `${this.host}/${this.session.podName()}/slices/${sliceName}/changes`;
    return this.http
      .post<void>(url, input, {
        headers: new HttpHeaders().append(
          'Content-Type',
          'application/ld+json',
        ),
        observe: 'response',
      })
      .pipe(this.convertErrorToKvasirError())
      .pipe(map((response) => response.headers.get('Location')));
  }

  listSliceChangeReports(
    sliceName: string,
    tag: string | null,
  ): Observable<ProcessedChange[]> {
    return this.http
      .get<GraphLD<ProcessedChange>>(
        tag
          ? `${this.host}/${this.session.podName()}/slices/${sliceName}/tags/${tag}/changes`
          : `${this.host}/${this.session.podName()}/slices/${sliceName}/changes`,
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

  previewSlice(input: SliceInput): Observable<string> {
    return this.http
      .put(`${this.host}/${this.session.podName()}/slices`, input, {
        headers: new HttpHeaders().append(
          'Content-Type',
          'application/ld+json',
        ),
        responseType: 'text',
      })
      .pipe(
        catchError((err, obs) =>
          of('SDL preview failed... (check your schema and @context again)'),
        ),
      )
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

  listSlices(): Observable<SliceSummary[]> {
    return this.http
      .get<GraphLD<Slice>>(`${this.host}/${this.session.podName()}/slices`)
      .pipe(this.convertErrorToKvasirError())
      .pipe(map((sliceLd) => sliceLd['@graph']));
  }

  listSliceTags(sliceId: string): Observable<GraphLD<EntityTag>> {
    return this.http
      .get<
        GraphLD<EntityTag>
      >(`${this.host}/${this.session.podName()}/slices/${sliceId}/tags`)
      .pipe(this.convertErrorToKvasirError());
  }

  aliasTag(sliceId: string, tag: EntityTag, alias: string): Observable<void> {
    return this.http
      .put<void>(
        `${this.host}/${this.session.podName()}/slices/${sliceId}/tags/${tag['kss:tag']}/alias/${alias}`,
        null,
      )
      .pipe(this.convertErrorToKvasirError());
  }

  updateTaggedSlice(
    sliceId: string,
    tag: string,
    slice: SliceInput,
  ): Observable<void> {
    return this.http
      .put<void>(
        `${this.host}/${this.session.podName()}/slices/${sliceId}/tags/${tag}`,
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

  getTaggedSlice(sliceId: string, tag: string): Observable<Slice> {
    return this.http
      .get<Slice>(
        `${this.host}/${this.session.podName()}/slices/${sliceId}/tags/${tag}`,
        { headers: new HttpHeaders().append('Accept', 'application/ld+json') },
      )
      .pipe(this.convertErrorToKvasirError());
  }

  deleteTag(sliceId: string, tag: string): Observable<void> {
    return this.http
      .delete<void>(
        `${this.host}/${this.session.podName()}/slices/${sliceId}/tags/${tag}`,
      )
      .pipe(this.convertErrorToKvasirError());
  }

  getSameRevisionTags(
    sliceId: string,
    revisionId: string,
  ): Observable<EntityTag[]> {
    return this.listSliceTags(sliceId).pipe(
      map((tagsLd) => tagsLd['@graph']),
      map((tags) => tags.filter((t) => t['kss:revisionId'] === revisionId)),
      this.convertErrorToKvasirError(),
    );
  }

  listPods(): Observable<Pod[]> {
    return this.http
      .get<GraphLD<Pod>>(this.host, {
        headers: new HttpHeaders().append('Accept', 'application/ld+json'),
      })
      .pipe(
        map((podsLd) => podsLd['@graph']),
        this.convertErrorToKvasirError(),
      );
  }

  getPod(): Observable<PodDetails> {
    return this.http
      .get<PodSerialized>(`${this.host}/${this.session.podName()}`)
      .pipe(
        map((podSer) => deserialize(podSer)),
        this.convertErrorToKvasirError(),
      );
  }

  getPodRuntimeConfig(): Observable<PodConfiguration> {
    return this.http
      .get<PodConfiguration>(
        `${this.host}/${this.session.podName()}/runtime-config`,
      )
      .pipe(this.convertErrorToKvasirError());
  }

  getPlatformConfig(): Observable<PodConfiguration> {
    return this.http
      .get<PodConfiguration>(
        `${this.host}/${this.session.podName()}/platform-config`,
      )
      .pipe(this.convertErrorToKvasirError());
  }

  updatePod(podConfig: PodConfiguration): Observable<void> {
    const cfg = {
      '@context': {
        kss: 'https://kvasir.discover.ilabt.imec.be/vocab#',
      },
      'kss:configuration': JSON.stringify(podConfig),
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
    const input: LD<RegisterPodInput> = {
      '@context': {
        kss: 'https://kvasir.discover.ilabt.imec.be/vocab#',
      },
      'kss:name': podName,
      'kss:ownerUserId': podName,
      'kss:configuration': '{}',
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
      const err = this.errorHandler.mapErrorToKvasirError(res);
      this.errorHandler.showInModal(err);
      return throwError(() => err);
    });
  };
}
