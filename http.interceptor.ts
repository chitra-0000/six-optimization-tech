import { Injectable } from '@angular/core';
import {
  HttpErrorResponse,
  HttpEvent,
  HttpHandler,
  HttpInterceptor,
  HttpRequest,
  HttpResponse,
  HttpParams,
  HttpXsrfTokenExtractor,
} from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';
import { tap, catchError } from 'rxjs/operators';
import { environment } from '../../../environments/environment';
import { Router } from '@angular/router';
import { User } from '../header/user/user';
import { SharedDataUser } from '../header/user/shared.data';
import { ErrorService } from '../services/error.service';
import { LoggedInUserDto } from '@dto/LoggedInUserDto';
import { request } from 'https';

@Injectable()
export class HTTPInterceptor implements HttpInterceptor {
  authenticatedUser: LoggedInUserDto;

  constructor(private sharedData: SharedDataUser, private router: Router, private errorService: ErrorService, private tokenService: HttpXsrfTokenExtractor) {
    this.sharedData.authenticatedUser.subscribe((data) => {
      this.authenticatedUser = data;
    });
  }

  intercept(
    req: HttpRequest<any>,
    next: HttpHandler
  ): Observable<HttpEvent<any>> {
    let baseReq: HttpRequest<any> = req;
    if (baseReq !== null && !environment.baseUrl.includes('regliss-app') && !environment.enableExternalMode) {
      baseReq = req.clone({



        headers: req.headers.set('X-XSRF-Token', this.tokenService.getToken()),

        withCredentials: true

      });
    }

    return next.handle(baseReq).pipe(
      tap(
        (event: HttpEvent<any>) => {
          if (event instanceof HttpResponse) {
            // OK
          }
        },
        (err: any) =>
        {
          if(err.status === 401 || err.status === 403 || err.status === 302){
            this.router.navigateByUrl('/forbidden');
          }
        }
      ),
      catchError((error: HttpErrorResponse) => {
        return throwError(error);
      })
    );
  }
}

