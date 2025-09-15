import { inject, Injectable } from '@angular/core';
import { NzModalService } from 'ng-zorro-antd/modal';

import {
  ErrorComponent,
  KvasirError,
} from '../components/error/error.component';
import {
  HttpErrorResponse,
  HttpHeaders,
  HttpResponse,
} from '@angular/common/http';

interface KvasirBackendError {
  details: string;
  stack: string;
}

@Injectable({
  providedIn: 'root',
})
export class ErrorHandlerService {
  private modal = inject(NzModalService);

  async mapResponseToKvasirError(res: Response): Promise<KvasirError> {
    const clonedRes = res.clone();
    const { status, statusText, headers, url } = clonedRes;
    const error = await clonedRes.json();
    const errRes = new HttpErrorResponse({
      status,
      statusText,
      headers: new HttpHeaders(headers),
      url,
      error,
    });
    return this.mapErrorToKvasirError(errRes);
  }

  mapErrorToKvasirError(res: HttpErrorResponse): KvasirError {
    // Determine if response is of type string or json
    const isStringError = typeof res.error == 'string';
    return {
      statusCode: res.status,
      message: isStringError
        ? res.error
        : ((res.error as KvasirBackendError)?.details ?? ''),
      stack: isStringError
        ? undefined
        : (res.error as KvasirBackendError)?.stack,
    };
  }

  showInModal(err: KvasirError) {
    let nzTitle;
    let description;
    switch (err.statusCode) {
      case 400:
        nzTitle = 'Error: Bad Request (400)';
        description =
          'The format and structure of the body that was sent to the server is probably incorrect.';
        break;

      case 401:
        nzTitle = 'Error: Unauthorized (401)';
        description =
          'You are considered unauthorized, an Authorization header is probably missing.';
        break;

      case 403:
        nzTitle = 'Error: Forbidden (403)';
        description =
          'The authorization token sent, did not have the proper authorization grants to take this action.';
        break;

      case 404:
        nzTitle = 'Error: Not Found (404)';
        description = 'The endpoints that was contacted, does not exist.';
        break;

      case 405:
        nzTitle = 'Error: Method Not Allowed (405)';
        description =
          'The HTTP method used to contact the endpoint, is not allowed on that endpoint.';
        break;

      case 409:
        nzTitle = 'Error: Conflict (409)';
        description =
          'The server responded with Conflict, which probably means you tried to create an entity that already exists (eg. same id/name)';
        break;

      case 415:
        nzTitle = 'Error: Unsupported Media Type (415)';
        description =
          'The media type (MIME type) of the content you sent, is not supported on this endpoint.';
        break;

      case 500:
        nzTitle = 'Error: Internal Server Error (500)';
        description =
          'An internal server error happened. Please report this as an issue to the developers.';
        break;

      case 502:
        nzTitle = 'Error: Bad Gateway (502)';
        description =
          'A service is not available for a response after proxying your request.';
        break;

      case 503:
        nzTitle = 'Error: Service Unavailable (503)';
        description =
          'A service in the backend was not available to answer your request. It is probably down for maintenance of because of failure.';
        break;

      case 504:
        nzTitle = 'Error: Gateway Timeout (504)';
        description =
          'A service could not respond in time after proxying your request.';
        break;

      default:
        nzTitle = 'Error: Unknown';
        description =
          'An unknown error occurred. Please report this as an issue to the developers.';
    }

    err.description = description;

    this.modal.create<ErrorComponent, KvasirError>({
      nzTitle,
      nzContent: ErrorComponent,
      nzData: err,
      nzWidth: '75%',
      nzFooter: null,
    });
  }
}
