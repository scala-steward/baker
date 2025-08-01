import {
  DigraphResponse,
  ServiceInformation,
  ExecuteInteractionRequest,
  ExecuteInteractionResponse,
  FireEventResponse,
  Instance,
  InstanceResponse,
  Interaction,
  InteractionExecutionFailure,
  InteractionExecutionSuccess,
  InteractionsResponse,
  NameAndValue,
  Recipe,
  RecipeBody,
  RecipeResponse,
  Recipes,
  ServiceError,
  SimplifiedEventInstance,
  SimplifiedFailureReason
} from "./bakery.api";
import {HttpClient, HttpHeaders} from "@angular/common/http";
import {Observable, of} from "rxjs";

import {catchError, map} from "rxjs/operators";
import {AppSettingsService} from "./app.settings";
import {BakerConversionService} from "./baker-conversion.service";

import {Injectable} from "@angular/core";
import {Value} from "./baker-value.api";

@Injectable({"providedIn": "root"})
export class BakeryService {

  private baseUrl = AppSettingsService.settings.apiPath;

  httpOptions = {
    "headers": new HttpHeaders({"Content-Type": "application/json"})
  };

  constructor(
    private http: HttpClient,
    private bakerConversionService: BakerConversionService
  ) {
  }

  getRecipes(): Observable<Recipe[]> {
    return this.http.get<Recipes>(`${this.baseUrl}/app/recipes`).pipe(map(recipes => Object.values(recipes.body)
      .map(response => {
        const row: Recipe = {
          "errors": response.errors,
          "name": response.compiledRecipe.name,
          "recipeCreatedTime": response.recipeCreatedTime,
          "recipeId": response.compiledRecipe.recipeId,
          "validate": response.validate,
        };
        return row;
      })));
  }

  getRecipe(recipeId: string): Observable<RecipeBody> {
    return this.http.get<RecipeResponse>(`${this.baseUrl}/app/recipes/${recipeId}`).pipe(map(response => response.body));
  }

  getRecipeVisual(recipeId: string): Observable<string> {
    return this.http.get<DigraphResponse>(`${this.baseUrl}/app/recipes/${recipeId}/visual`).pipe(map(response => response.body));
  }

  postBake(instanceId: string, recipeId: string): Observable<string> {
    return this.http.post<DigraphResponse>(`${this.baseUrl}/instances/${instanceId}/bake/${recipeId}`, null).pipe(map(response => response.body));
  }

  deactivateRecipe(recipeId: string): Observable<string> {
    return this.http.delete<string>(`${this.baseUrl}/app/recipes/${recipeId}`)
  }

  getInteractions(): Observable<Interaction[]> {
    return this.http.get<InteractionsResponse>(`${this.baseUrl}/app/interactions`).pipe(map(response => Object.values(response.body)));
  }

  getInstance(instanceId: string): Observable<Instance | null> {
    return this.http.get<InstanceResponse>(`${this.baseUrl}/instances/${instanceId}`).pipe(
      catchError(this.handleError<InstanceResponse>(null)),
      map(response => {
        if (response && response.result === "success") {
          return response.body;
        }
        return null;
      })
    );
  }

  deleteInstance(instanceId: string): Observable<string> {
    return this.http.delete<string>(`${this.baseUrl}/instances/${instanceId}/delete?removeFromIndex=true`)
  }

  getInstanceVisual(instanceId: string): Observable<string | null> {
    return this.http.get<DigraphResponse>(`${this.baseUrl}/instances/${instanceId}/visual`).pipe(
      catchError(this.handleError<DigraphResponse>(null)),
      map(response => {
        if (response && response.result === "success") {
          return response.body;
        }
        return null;
      })
    );
  }

  executeInteraction(interactionId: string, ingredients: NameAndValue[]): Observable<ServiceInformation<ExecuteInteractionResponse> | ServiceError> {
    const request: ExecuteInteractionRequest = {
      "id": interactionId,
      ingredients
    };

    const requestSentAt = new Date();
    return this.http.post<ExecuteInteractionResponse>(`${this.baseUrl}/app/interactions/execute`, request)
      .pipe(
        map(response => {
          const eii: ServiceInformation<ExecuteInteractionResponse> = {
            response,
            requestSentAt,
            "durationInMilliseconds": new Date().getTime() - requestSentAt.getTime(),
            "failureReason": BakeryService.getExecuteInteractionFailureReason(response),
            "successEvent": this.getExecuteInteractionSuccessEvent(response),
          };
          return eii;
        }),
        catchError(this.channelError(requestSentAt)),
      );
  }

  private getExecuteInteractionSuccessEvent(response: ExecuteInteractionResponse): SimplifiedEventInstance | null {
    if (Object.keys(response.body.outcome).includes("Right")) {
      const successOutcome = response.body.outcome as Record<"Right", Record<"value", InteractionExecutionSuccess>>;
      const eventInstance = successOutcome.Right.value.result;
      return {
        "name": eventInstance.name,
        "providedIngredients":
          Object.fromEntries(
            Object.entries(eventInstance.providedIngredients)
              .map(([key, val]) => [key, this.bakerConversionService.valueToJson(val)])),
      };
    }
    return null;
  }

  private static getExecuteInteractionFailureReason(response: ExecuteInteractionResponse): SimplifiedFailureReason | null {
    if (Object.keys(response.body.outcome).includes("Left")) {
      const failureOutcome = response.body.outcome as Record<"Left", Record<"value", InteractionExecutionFailure>>;
      // eslint-disable-next-line prefer-destructuring
      const [
        name,
        body
      ] = Object.entries(failureOutcome.Left.value.reason)[0];
      return {
        "reason": name,
        "interactionErrorMessage": body?.message,
      };
    }
    return null;
  }

  fireEvent(instanceId: string, name: string, ingredients: Record<string, Value>): Observable<ServiceInformation<FireEventResponse> | ServiceError> {

    const request = {
      name: name,
      providedIngredients: ingredients
    }

    const requestSentAt = new Date();
    return this.http.post<FireEventResponse>(`${this.baseUrl}/instances/${instanceId}/fire-and-resolve-when-completed`, request)
      .pipe(
        map(response => {
          const eii: ServiceInformation<FireEventResponse> = {
            response,
            requestSentAt,
            "durationInMilliseconds": new Date().getTime() - requestSentAt.getTime(),
            "failureReason": BakeryService.getFireEventFailureReason(response),
            "successEvent": this.getFireEventSuccessEvent(response),
          };
          return eii;
        }),
        catchError(this.channelError(requestSentAt)),
      );
  }


  private getFireEventSuccessEvent(response: FireEventResponse): SimplifiedEventInstance | null {
    if (response.result == "success") {
      if (response.body.sensoryEventStatus == "Completed") {
        return {
          "name": `Fired: ${response.body.eventNames.join(",")}`,
          "providedIngredients":  response.body.ingredients,
        };
      }
    }
    return null;
  }

  private static getFireEventFailureReason(response: FireEventResponse): SimplifiedFailureReason | null {
    if (response.result == "error") {

    }
    if (response.body.sensoryEventStatus != "Completed") {
      return {
        "reason": `Status: ${response.body.sensoryEventStatus} `,
        "interactionErrorMessage": response.body.sensoryEventStatus,
      };
    }
    return null;
  }

  private channelError(requestSentAt: Date) {
    return (error: any): Observable<ServiceError> => of({
      requestSentAt,
      "durationInMilliseconds": new Date().getTime() - requestSentAt.getTime(),
      error
    });
  }

  private handleError<T>(result: T | null) {
    return (error: any): Observable<T> => {
      // eslint-disable-next-line no-console
      console.log(`http request failed: ${error.message}`);
      return of(result as T);
    };
  }

}
