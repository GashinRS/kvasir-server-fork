import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import type {
  DeleteObjectCommandOutput,
  ListObjectsV2CommandOutput,
  PutObjectCommandOutput,
} from '@aws-sdk/client-s3';
import { XMLParser } from 'fast-xml-parser';
import { EMPTY, expand, firstValueFrom, map } from 'rxjs';
import { ConfigService } from './config.service';
import { SessionService } from './session.service';

@Injectable({
  providedIn: 'root',
})
export class S3wrapperService {
  private http = inject(HttpClient);
  private config = inject(ConfigService);
  private session = inject(SessionService);

  private url: string;
  private host = this.config.host;

  constructor() {
    this.url = `${this.host}/${this.session.podName()!}/s3`;
  }

  async listObjects(
    prefix?: string,
    continuationToken?: string,
  ): Promise<ListObjectsV2CommandOutput> {
    var url = `${this.url}/?list-type=2&encoding-type=url&delimiter=/`;
    if (prefix) {
      url += `&prefix=${encodeURIComponent(prefix)}`;
    }
    if (continuationToken) {
      url += `&continuation-token=${encodeURIComponent(continuationToken)}`;
    }
    return firstValueFrom(
      this.http
        .get(url, {
          responseType: 'text',
        })
        .pipe(
          map((str) =>
            s3toJson<ListObjectsV2CommandOutput>(str, 'ListBucketResult'),
          ),
          expand((result) => {
            if (result.IsTruncated && result.NextContinuationToken) {
              return this.listObjects(prefix, result.NextContinuationToken!);
            } else {
              return EMPTY;
            }
          }),
        ),
    );
  }

  async downloadObject(key: string): Promise<Blob> {
    const params = new URLSearchParams('x-id=GetObject');
    return firstValueFrom(
      this.http.get(`${this.url}/${key}?${params.toString()}`, {
        responseType: 'blob',
      }),
    );
  }

  async uploadObject(key: string, file: File): Promise<PutObjectCommandOutput> {
    const params = new URLSearchParams('x-id=PutObject');
    const buffer = await file.arrayBuffer();
    const fileType = file.type?.length > 0 ? file.type : this.keyToMIME(key);
    return firstValueFrom(
      this.http
        .put(`${this.url}/${key}?${params.toString()}`, buffer, {
          responseType: 'text',
          headers: {
            'Content-Type': fileType,
          },
        })
        .pipe(map((str) => s3toJson<PutObjectCommandOutput>(str))),
    );
  }

  async deleteObject(key: string): Promise<DeleteObjectCommandOutput> {
    const params = new URLSearchParams('x-id=DeleteObject');
    return firstValueFrom(
      this.http
        .delete(`${this.url}/${key}?${params.toString()}`, {
          responseType: 'text',
        })
        .pipe(map((str) => s3toJson<DeleteObjectCommandOutput>(str))),
    );
  }

  keyToMIME(key: string) {
    const ext = key.slice(key.lastIndexOf('.') + 1);
    switch (ext) {
      // images
      case 'bmp':
      case 'png':
      case 'gif':
      case 'svg':
      case 'svg+xml':
      case 'webp':
        return `image/${ext}`;

      case 'jpg':
      case 'jpeg':
        return `image/jpeg`;

      // text
      case 'txt':
        return 'text/plain';

      case 'csv':
      case 'html':
      case 'n3':
        return `text/${ext}`;

      case 'ttl':
        return 'text/turtle';

      // application
      case 'nt':
        return 'application/n-triples';

      case 'jsonld':
        return 'application/ld+json';

      case 'pdf':
      case 'zip':
        return `application/${ext}`;
      default:
        return 'application/octet-stream';
    }
  }
}

const parser = new XMLParser({
  ignorePiTags: true,
});

function s3toJson<T>(xml: string, key?: string): T {
  const tx = parser.parse(xml || '');
  return (key ? tx[key] : tx) as T;
}
