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
  CheckResult,
  GraphLD,
  Paged,
  Pod,
  PodConfiguration,
  Relationship,
  RelationshipDefinition,
  Slice,
  SliceInput,
  WriteTransaction,
} from '../types';
import { parseLinkHeader } from '../util/utils';
import { ConfigService } from './config.service';
import { ErrorHandlerService } from './error-handler.service';
import { SessionService } from './session.service';

@Injectable({
  providedIn: 'root',
})
export class RebacService {
  private config = inject(ConfigService);
  private http = inject(HttpClient);
  private session = inject(SessionService);
  private errorHandler = inject(ErrorHandlerService);

  private host = this.config.host;

  constructor() {}

  /**
   * List all relationships of this pod
   * @returns
   */
  listRelationships(): Observable<Relationship[]> {
    return this.http
      .get<GraphLD<RelationshipDefinition>>(
        `${this.host}/${this.session.podName()}/rebac/relationships`,
      )
      .pipe(this.convertErrorToKvasirError())
      .pipe(map((collectionLd) => collectionLd['@graph'] ?? [collectionLd]))
      .pipe(map((relations) => relations.map((el) => new Relationship(el))));
  }

  /**
   * Create a new relationship transaction (insert or delete) in this pod
   * @param transaction
   * @returns
   */
  postRelationship(transaction: WriteTransaction): Observable<any> {
    return this.http
      .post<void>(
        `${this.host}/${this.session.podName()}/rebac/relationships`,
        transaction,
        {
          headers: new HttpHeaders().append(
            'Content-Type',
            'application/ld+json',
          ),
        },
      )
      .pipe(this.convertErrorToKvasirError());
  }

  /**
   * Check a new relationship in this pod
   * @param input
   * @returns
   */
  check(input: any): Observable<CheckResult> {
    return this.http
      .post<CheckResult>(
        `${this.host}/${this.session.podName()}/rebac/check`,
        input,
        {
          headers: new HttpHeaders().append(
            'Content-Type',
            'application/ld+json',
          ),
        },
      )
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
